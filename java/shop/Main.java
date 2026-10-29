package shop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Executors;

import shop.SimpleCrud.Column;
import shop.SimpleCrud.ColumnType;

public final class Main {
    /** Простые таблицы, для которых работает типовой CRUD: имя ресурса в URL -> обработчик. */
    private static final Map<String, SimpleCrud> CRUD_BY_RESOURCE_NAME = Map.of(
            "customers", new SimpleCrud("customers",
                    new Column("full_name", ColumnType.TEXT, true),
                    new Column("email", ColumnType.TEXT, true),
                    new Column("phone", ColumnType.TEXT, false)),
            "categories", new SimpleCrud("categories",
                    new Column("name", ColumnType.TEXT, true),
                    new Column("description", ColumnType.TEXT, false)),
            "products", new SimpleCrud("products",
                    new Column("category_id", ColumnType.INTEGER, true),
                    new Column("name", ColumnType.TEXT, true),
                    new Column("price", ColumnType.DECIMAL, true),
                    new Column("stock", ColumnType.INTEGER, false)));

    public static void main(String[] args) throws Exception {
        int serverPort = Integer.parseInt(Optional.ofNullable(System.getenv("PORT")).orElse("8080"));
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(serverPort), 0);
        httpServer.createContext("/", Main::handleRequest);
        httpServer.setExecutor(Executors.newFixedThreadPool(8));
        httpServer.start();
        System.out.println("Сервер запущен: http://localhost:" + serverPort + "/");
        System.out.println("БД: " + Db.DATABASE_URL + " (пользователь " + Db.DATABASE_USER + ")");
    }

    /** Принимает HTTP-запрос, вызывает нужную логику и отправляет ответ (JSON или HTML-страницу). */
    private static void handleRequest(HttpExchange exchange) {
        int httpStatus = 200;
        String responseBody;
        String contentType = "application/json; charset=utf-8";
        try {
            String requestPath = exchange.getRequestURI().getPath();
            if (requestPath.equals("/") || requestPath.equals("/index.html")) {
                try (InputStream pageStream = Main.class.getResourceAsStream("/static/index.html")) {
                    if (pageStream == null) throw new ApiException(404, "index.html не найден");
                    responseBody = new String(pageStream.readAllBytes(), StandardCharsets.UTF_8);
                    contentType = "text/html; charset=utf-8";
                }
            } else {
                String httpMethod = exchange.getRequestMethod().toUpperCase();
                String requestBodyText = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> requestBody = null;
                if (!requestBodyText.isBlank()) {
                    Object parsedJson = Json.parse(requestBodyText);
                    if (!(parsedJson instanceof Map<?, ?>))
                        throw new ApiException(400, "Тело запроса должно быть JSON-объектом");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> bodyAsMap = (Map<String, Object>) parsedJson;
                    requestBody = bodyAsMap;
                }
                int[] statusHolder = {200}; // обработчик может заменить статус (например, на 201)
                Map<String, String> queryParameters = parseQueryString(exchange.getRequestURI().getRawQuery());
                Object result = route(httpMethod, requestPath, queryParameters, requestBody, statusHolder);
                httpStatus = statusHolder[0];
                responseBody = Json.write(result);
            }
        } catch (ApiException apiException) {
            httpStatus = apiException.httpStatus;
            responseBody = Json.write(Map.of("error", String.valueOf(apiException.getMessage())));
        } catch (IllegalArgumentException invalidJson) {
            httpStatus = 400;
            responseBody = Json.write(Map.of("error", String.valueOf(invalidJson.getMessage())));
        } catch (SQLException sqlException) {
            String sqlState = sqlException.getSQLState() == null ? "" : sqlException.getSQLState();
            httpStatus = switch (sqlState) {
                case "23503", "23505", "40001", "40P01" -> 409; // нарушение FK/уникальности, конфликт транзакций
                case "23514", "23502", "22023", "22001", "22003" -> 400; // неверные данные
                default -> 500;
            };
            Map<String, Object> errorBody = new LinkedHashMap<>();
            errorBody.put("error", String.valueOf(sqlException.getMessage()));
            errorBody.put("sqlstate", sqlException.getSQLState());
            responseBody = Json.write(errorBody);
        } catch (Exception unexpectedException) {
            unexpectedException.printStackTrace();
            httpStatus = 500;
            responseBody = Json.write(Map.of("error", String.valueOf(unexpectedException)));
        }

        try {
            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(httpStatus, responseBytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(responseBytes);
            }
        } catch (Exception ignoredSendFailure) {
            // клиент мог закрыть соединение - отвечать уже некому
        } finally {
            exchange.close();
        }
    }

    /** Выбирает действие по методу и пути запроса, например GET /api/orders/5. */
    private static Object route(String httpMethod, String requestPath, Map<String, String> queryParameters,
                                Map<String, Object> requestBody, int[] statusHolder) throws SQLException {
        List<String> pathSegments = new ArrayList<>();
        for (String segment : requestPath.split("/")) {
            if (!segment.isEmpty()) pathSegments.add(segment);
        }
        if (pathSegments.size() < 2 || !pathSegments.get(0).equals("api"))
            throw new ApiException(404, "Не найдено: " + requestPath);

        String resourceName = pathSegments.get(1);
        String thirdSegment = pathSegments.size() > 2 ? pathSegments.get(2) : null;
        Integer recordId = (thirdSegment != null && thirdSegment.matches("\\d{1,9}"))
                ? Integer.valueOf(thirdSegment) : null;

        // Клиенты, категории, товары - типовой CRUD
        if (CRUD_BY_RESOURCE_NAME.containsKey(resourceName)) {
            SimpleCrud crud = CRUD_BY_RESOURCE_NAME.get(resourceName);
            if (thirdSegment == null) {
                if (httpMethod.equals("GET")) return crud.listAll();
                if (httpMethod.equals("POST")) {
                    statusHolder[0] = 201;
                    return crud.create(requireBody(requestBody));
                }
            } else if (recordId != null) {
                if (httpMethod.equals("GET")) return crud.findById(recordId);
                if (httpMethod.equals("PUT")) return crud.update(recordId, requireBody(requestBody));
                if (httpMethod.equals("DELETE")) return crud.delete(recordId);
            }
            throw new ApiException(405, "Метод/путь не поддерживается");
        }

        switch (resourceName) {
            case "orders" -> {
                if (thirdSegment == null) {
                    if (httpMethod.equals("GET")) return OrderService.listOrders();
                    if (httpMethod.equals("POST")) {
                        statusHolder[0] = 201;
                        return OrderService.createOrder(requireBody(requestBody));
                    }
                } else if (recordId != null) {
                    if (httpMethod.equals("GET")) return OrderService.getOrder(recordId);
                    if (httpMethod.equals("PUT")) return OrderService.updateOrder(recordId, requireBody(requestBody));
                    if (httpMethod.equals("DELETE")) return OrderService.deleteOrder(recordId);
                }
            }
            case "reports" -> {
                if (httpMethod.equals("GET") && "sales-by-category".equals(thirdSegment))
                    return Routines.reportSalesByCategory();
                if (httpMethod.equals("GET") && "top-customers".equals(thirdSegment))
                    return Routines.reportTopCustomers();
            }
            case "functions" -> {
                boolean isCustomerTotalPath = "customer-total".equals(thirdSegment)
                        && pathSegments.size() == 4 && pathSegments.get(3).matches("\\d{1,9}");
                if (httpMethod.equals("GET") && isCustomerTotalPath)
                    return Routines.callCustomerTotalFunction(Integer.parseInt(pathSegments.get(3)));
            }
            case "procedures" -> {
                if (httpMethod.equals("POST") && "apply-discount".equals(thirdSegment)) {
                    Map<String, Object> body = requireBody(requestBody);
                    return Routines.callApplyDiscountProcedure(
                            RequestFields.readInteger(body, "category_id"), RequestFields.readDecimal(body, "percent"));
                }
            }
            case "isolation" -> {
                if (httpMethod.equals("GET") && "run".equals(thirdSegment)) {
                    String anomalyName = queryParameters.get("anomaly");
                    String levelName = queryParameters.get("level");
                    if (anomalyName == null || levelName == null)
                        throw new ApiException(400, "Нужны параметры anomaly и level");
                    return IsolationDemo.runScenario(anomalyName, levelName);
                }
                if (httpMethod.equals("GET") && "report".equals(thirdSegment))
                    return IsolationDemo.runAllScenarios();
            }
            default -> { }
        }
        throw new ApiException(404, "Не найдено: " + httpMethod + " " + requestPath);
    }

    private static Map<String, Object> requireBody(Map<String, Object> requestBody) {
        if (requestBody == null) throw new ApiException(400, "Нужно тело запроса (JSON)");
        return requestBody;
    }

    /** Разбирает строку запроса вида "anomaly=dirty_read&level=SERIALIZABLE" в Map. */
    private static Map<String, String> parseQueryString(String rawQuery) {
        Map<String, String> parameters = new HashMap<>();
        if (rawQuery == null) return parameters;
        for (String pair : rawQuery.split("&")) {
            int separatorIndex = pair.indexOf('=');
            if (separatorIndex > 0) {
                String name = URLDecoder.decode(pair.substring(0, separatorIndex), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(separatorIndex + 1), StandardCharsets.UTF_8);
                parameters.put(name, value);
            }
        }
        return parameters;
    }
}
