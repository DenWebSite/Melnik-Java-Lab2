package shop;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;

/** Вызов хранимой функции и хранимой процедуры из приложения + отчёты. */
public final class Routines {
    private Routines() {}

    /** Функция fn_customer_total(int) через CallableStatement. */
    public static Map<String, Object> callCustomerTotalFunction(int customerId) throws SQLException {
        return Db.withConnection(connection -> {
            Map<String, Object> customer = Db.selectOne(connection,
                    "SELECT id, full_name FROM customers WHERE id = ?", customerId);
            if (customer == null) throw new ApiException(404, "Клиент id=" + customerId + " не найден");

            BigDecimal totalSpent;
            try (CallableStatement functionCall = connection.prepareCall("{ ? = call fn_customer_total(?) }")) {
                functionCall.registerOutParameter(1, Types.NUMERIC);
                functionCall.setInt(2, customerId);
                functionCall.execute();
                totalSpent = functionCall.getBigDecimal(1);
            }

            Map<String, Object> response = new LinkedHashMap<>(customer);
            response.put("total_spent", totalSpent);
            return response;
        });
    }

    /** Процедура sp_apply_discount(int, numeric) через CALL, в транзакции. */
    public static Map<String, Object> callApplyDiscountProcedure(int categoryId, BigDecimal discountPercent)
            throws SQLException {
        return Db.inTransaction(connection -> {
            if (Db.selectOne(connection, "SELECT 1 AS found FROM categories WHERE id = ?", categoryId) == null)
                throw new ApiException(404, "Категория id=" + categoryId + " не найдена");

            try (CallableStatement procedureCall = connection.prepareCall("CALL sp_apply_discount(?, ?)")) {
                procedureCall.setInt(1, categoryId);
                procedureCall.setBigDecimal(2, discountPercent);
                procedureCall.execute();
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("category_id", categoryId);
            response.put("percent", discountPercent);
            response.put("products", Db.selectRows(connection,
                    "SELECT id, name, price FROM products WHERE category_id = ? ORDER BY id", categoryId));
            return response;
        });
    }

    /** Отчёт: продажи по категориям (JOIN + GROUP BY). Отменённые заказы не учитываются. */
    public static List<Map<String, Object>> reportSalesByCategory() throws SQLException {
        return Db.withConnection(connection -> Db.selectRows(connection,
                "SELECT cat.name AS category, "
                        + "COUNT(DISTINCT o.id) AS orders_count, "
                        + "COALESCE(SUM(CASE WHEN o.id IS NULL THEN 0 ELSE oi.quantity END), 0) AS items_sold, "
                        + "COALESCE(SUM(CASE WHEN o.id IS NULL THEN 0 ELSE oi.quantity * oi.unit_price END), 0) AS revenue "
                        + "FROM categories cat "
                        + "LEFT JOIN products p ON p.category_id = cat.id "
                        + "LEFT JOIN order_items oi ON oi.product_id = p.id "
                        + "LEFT JOIN orders o ON o.id = oi.order_id AND o.status <> 'CANCELLED' "
                        + "GROUP BY cat.id, cat.name ORDER BY revenue DESC"));
    }

    /** Отчёт: клиенты по сумме заказов (использует хранимую функцию прямо в SELECT). */
    public static List<Map<String, Object>> reportTopCustomers() throws SQLException {
        return Db.withConnection(connection -> Db.selectRows(connection,
                "SELECT id, full_name, fn_customer_total(id) AS total_spent "
                        + "FROM customers ORDER BY total_spent DESC, id"));
    }
}
