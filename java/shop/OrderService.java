package shop;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Заказы: родительская таблица orders + дочерняя order_items.
 * Вставка / обновление / удаление выполняются в ОДНОЙ транзакции сразу в обеих
 * таблицах
 * (плюс корректируются остатки в products).
 */
public final class OrderService {
    private OrderService() {
    }

    private static final Set<String> ALLOWED_STATUSES = Set.of("NEW", "PAID", "SHIPPED", "CANCELLED");

    public static List<Map<String, Object>> listOrders() throws SQLException {
        return Db.withConnection(connection -> Db.selectRows(connection,
                "SELECT o.id, o.status, o.total, o.created_at, c.id AS customer_id, c.full_name AS customer, "
                        + "(SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.id) AS positions "
                        + "FROM orders o JOIN customers c ON c.id = o.customer_id ORDER BY o.id"));
    }

    public static Map<String, Object> getOrder(int orderId) throws SQLException {
        Map<String, Object> order = Db.withConnection(connection -> loadOrderWithItems(connection, orderId));
        if (order == null)
            throw new ApiException(404, "Заказ id=" + orderId + " не найден");
        return order;
    }

    /**
     * Загружает заказ вместе с его позициями; если заказа нет - возвращает null.
     */
    private static Map<String, Object> loadOrderWithItems(Connection connection, int orderId) throws SQLException {
        Map<String, Object> order = Db.selectOne(connection,
                "SELECT o.id, o.status, o.total, o.created_at, c.id AS customer_id, c.full_name AS customer "
                        + "FROM orders o JOIN customers c ON c.id = o.customer_id WHERE o.id = ?",
                orderId);
        if (order == null)
            return null;
        order.put("items", Db.selectRows(connection,
                "SELECT oi.id, oi.product_id, p.name AS product, oi.quantity, oi.unit_price, "
                        + "(oi.quantity * oi.unit_price) AS line_total "
                        + "FROM order_items oi JOIN products p ON p.id = oi.product_id "
                        + "WHERE oi.order_id = ? ORDER BY oi.id",
                orderId));
        return order;
    }

    /**
     * CREATE: INSERT в orders + INSERT в order_items + UPDATE products + UPDATE
     * orders.total.
     */
    public static Map<String, Object> createOrder(Map<String, Object> requestBody) throws SQLException {
        int customerId = RequestFields.readInteger(requestBody, "customer_id");
        Object itemsFromRequest = requestBody.get("items");
        return Db.inTransaction(connection -> {
            if (Db.selectOne(connection, "SELECT 1 AS found FROM customers WHERE id = ?", customerId) == null)
                throw new ApiException(404, "Клиент id=" + customerId + " не найден");
            Map<String, Object> insertedOrder = Db.selectOne(connection,
                    "INSERT INTO orders(customer_id, status, total) VALUES (?, 'NEW', 0) RETURNING id", customerId);
            int newOrderId = ((Number) insertedOrder.get("id")).intValue();
            BigDecimal orderTotal = insertOrderItems(connection, newOrderId, itemsFromRequest);
            Db.executeUpdate(connection, "UPDATE orders SET total = ? WHERE id = ?", orderTotal, newOrderId);
            return loadOrderWithItems(connection, newOrderId);
        });
    }

    // UPDATE: меняет статус и/или полностью заменяет позиции заказа.

    public static Map<String, Object> updateOrder(int orderId, Map<String, Object> requestBody) throws SQLException {
        return Db.inTransaction(connection -> {
            Map<String, Object> existingOrder = Db.selectOne(connection,
                    "SELECT * FROM orders WHERE id = ? FOR UPDATE", orderId);
            if (existingOrder == null)
                throw new ApiException(404, "Заказ id=" + orderId + " не найден");

            String oldStatus = (String) existingOrder.get("status");
            String newStatus = requestBody.get("status") == null ? oldStatus
                    : String.valueOf(requestBody.get("status"));
            if (!ALLOWED_STATUSES.contains(newStatus))
                throw new ApiException(400, "Недопустимый статус. Допустимы: " + ALLOWED_STATUSES);
            Object newItemsFromRequest = requestBody.get("items");

            boolean orderWasAlreadyCancelled = oldStatus.equals("CANCELLED");
            boolean requestChangesCancelledOrder = newItemsFromRequest != null || !newStatus.equals("CANCELLED");
            if (orderWasAlreadyCancelled && requestChangesCancelledOrder)
                throw new ApiException(409, "Отменённый заказ изменять нельзя");

            if (newStatus.equals("CANCELLED")) {
                returnItemsToStock(connection, orderId); // возврат товара на склад
                Db.executeUpdate(connection, "UPDATE orders SET status = 'CANCELLED' WHERE id = ?", orderId);
            } else if (newItemsFromRequest != null) {
                returnItemsToStock(connection, orderId);
                Db.executeUpdate(connection, "DELETE FROM order_items WHERE order_id = ?", orderId);
                BigDecimal newOrderTotal = insertOrderItems(connection, orderId, newItemsFromRequest);
                Db.executeUpdate(connection, "UPDATE orders SET status = ?, total = ? WHERE id = ?",
                        newStatus, newOrderTotal, orderId);
            } else {
                Db.executeUpdate(connection, "UPDATE orders SET status = ? WHERE id = ?", newStatus, orderId);
            }
            return loadOrderWithItems(connection, orderId);
        });
    }

    /** DELETE: сначала дочерние строки, затем родитель - в одной транзакции. */
    public static Map<String, Object> deleteOrder(int orderId) throws SQLException {
        return Db.inTransaction(connection -> {
            Map<String, Object> existingOrder = Db.selectOne(connection,
                    "SELECT * FROM orders WHERE id = ? FOR UPDATE", orderId);
            if (existingOrder == null)
                throw new ApiException(404, "Заказ id=" + orderId + " не найден");
            if (!"CANCELLED".equals(existingOrder.get("status")))
                returnItemsToStock(connection, orderId);
            int deletedItemsCount = Db.executeUpdate(connection, "DELETE FROM order_items WHERE order_id = ?", orderId);
            Db.executeUpdate(connection, "DELETE FROM orders WHERE id = ?", orderId);
            return Map.of("deleted", orderId, "deleted_items", deletedItemsCount);
        });
    }

    // ---------- внутренние помощники ----------

    /**
     * Возвращает на склад все товары заказа (при отмене, замене позиций или
     * удалении заказа).
     */
    private static void returnItemsToStock(Connection connection, int orderId) throws SQLException {
        Db.executeUpdate(connection, "UPDATE products p SET stock = p.stock + oi.quantity "
                + "FROM order_items oi WHERE oi.order_id = ? AND oi.product_id = p.id", orderId);
    }

    /**
     * Добавляет позиции в заказ, списывает товар со склада и возвращает общую сумму
     * заказа.
     * Одинаковые товары в запросе объединяются (количества суммируются).
     */
    private static BigDecimal insertOrderItems(Connection connection, int orderId, Object itemsFromRequest)
            throws SQLException {
        if (!(itemsFromRequest instanceof List<?> itemsList) || itemsList.isEmpty())
            throw new ApiException(400, "Заказ должен содержать хотя бы одну позицию (items)");

        // TreeMap: товары обрабатываются в одном и том же порядке, это защищает от
        // взаимных блокировок
        Map<Integer, Integer> quantityByProductId = new TreeMap<>();
        for (Object itemObject : itemsList) {
            if (!(itemObject instanceof Map<?, ?> itemJson))
                throw new ApiException(400, "Позиция заказа должна быть объектом");
            int productId = RequestFields.readInteger(itemJson, "product_id");
            int quantity = RequestFields.readInteger(itemJson, "quantity");
            if (quantity <= 0)
                throw new ApiException(400, "quantity должен быть > 0");
            quantityByProductId.merge(productId, quantity, Integer::sum);
        }

        BigDecimal orderTotal = BigDecimal.ZERO;
        for (Map.Entry<Integer, Integer> productEntry : quantityByProductId.entrySet()) {
            int productId = productEntry.getKey();
            int orderedQuantity = productEntry.getValue();

            Map<String, Object> product = Db.selectOne(connection,
                    "SELECT price, stock FROM products WHERE id = ? FOR UPDATE", productId);
            if (product == null)
                throw new ApiException(404, "Товар id=" + productId + " не найден");

            int stockQuantity = ((Number) product.get("stock")).intValue();
            BigDecimal productPrice = (BigDecimal) product.get("price");
            if (stockQuantity < orderedQuantity)
                throw new ApiException(409, "Недостаточно товара id=" + productId
                        + " на складе (есть " + stockQuantity + ")");

            Db.executeUpdate(connection,
                    "INSERT INTO order_items(order_id, product_id, quantity, unit_price) VALUES (?,?,?,?)",
                    orderId, productId, orderedQuantity, productPrice);
            Db.executeUpdate(connection, "UPDATE products SET stock = stock - ? WHERE id = ?",
                    orderedQuantity, productId);
            orderTotal = orderTotal.add(productPrice.multiply(BigDecimal.valueOf(orderedQuantity)));
        }
        return orderTotal;
    }
}
