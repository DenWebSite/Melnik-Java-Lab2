package shop;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.*;

/** CRUD для простых справочных таблиц (customers, categories, products). Все изменения - в транзакции. */
public final class SimpleCrud {
    public enum ColumnType { TEXT, INTEGER, DECIMAL }

    /** Описание колонки таблицы, доступной для записи через API. */
    public record Column(String name, ColumnType type, boolean isRequired) {}

    private final String tableName;
    private final List<Column> columns;

    public SimpleCrud(String tableName, Column... columns) {
        this.tableName = tableName;
        this.columns = List.of(columns);
    }

    public List<Map<String, Object>> listAll() throws SQLException {
        return Db.withConnection(connection ->
                Db.selectRows(connection, "SELECT * FROM " + tableName + " ORDER BY id"));
    }

    public Map<String, Object> findById(int recordId) throws SQLException {
        Map<String, Object> foundRecord = Db.withConnection(connection ->
                Db.selectOne(connection, "SELECT * FROM " + tableName + " WHERE id = ?", recordId));
        if (foundRecord == null) throw new ApiException(404, "Запись id=" + recordId + " не найдена в " + tableName);
        return foundRecord;
    }

    public Map<String, Object> create(Map<String, Object> requestBody) throws SQLException {
        List<String> columnNames = new ArrayList<>();
        List<Object> columnValues = new ArrayList<>();
        for (Column column : columns) {
            Object receivedValue = requestBody.get(column.name());
            if (receivedValue == null) {
                if (column.isRequired()) throw new ApiException(400, "Поле '" + column.name() + "' обязательно");
                continue;
            }
            columnNames.add(column.name());
            columnValues.add(convertValue(column, receivedValue));
        }
        String placeholders = "?,".repeat(columnNames.size()).replaceAll(",$", "");
        String insertSql = "INSERT INTO " + tableName + "(" + String.join(",", columnNames) + ") VALUES ("
                + placeholders + ") RETURNING *";
        return Db.inTransaction(connection -> Db.selectOne(connection, insertSql, columnValues.toArray()));
    }

    public Map<String, Object> update(int recordId, Map<String, Object> requestBody) throws SQLException {
        List<String> assignments = new ArrayList<>(); // фрагменты вида "price = ?"
        List<Object> parameterValues = new ArrayList<>();
        for (Column column : columns) {
            if (!requestBody.containsKey(column.name())) continue;
            assignments.add(column.name() + " = ?");
            parameterValues.add(convertValue(column, requestBody.get(column.name())));
        }
        if (assignments.isEmpty()) throw new ApiException(400, "Нет полей для обновления");
        parameterValues.add(recordId);
        String updateSql = "UPDATE " + tableName + " SET " + String.join(", ", assignments)
                + " WHERE id = ? RETURNING *";
        return Db.inTransaction(connection -> {
            Map<String, Object> updatedRecord = Db.selectOne(connection, updateSql, parameterValues.toArray());
            if (updatedRecord == null)
                throw new ApiException(404, "Запись id=" + recordId + " не найдена в " + tableName);
            return updatedRecord;
        });
    }

    public Map<String, Object> delete(int recordId) throws SQLException {
        int deletedRowsCount = Db.inTransaction(connection ->
                Db.executeUpdate(connection, "DELETE FROM " + tableName + " WHERE id = ?", recordId));
        if (deletedRowsCount == 0)
            throw new ApiException(404, "Запись id=" + recordId + " не найдена в " + tableName);
        return Map.of("deleted", recordId);
    }

    /** Приводит значение из JSON к типу колонки; при несовпадении типа отвечает ошибкой 400. */
    private Object convertValue(Column column, Object jsonValue) {
        if (jsonValue == null) {
            if (column.isRequired()) throw new ApiException(400, "Поле '" + column.name() + "' не может быть null");
            return null;
        }
        try {
            return switch (column.type()) {
                case TEXT -> {
                    if (!(jsonValue instanceof String)) throw new ClassCastException();
                    yield jsonValue;
                }
                case INTEGER -> (Object) ((BigDecimal) jsonValue).intValueExact();
                case DECIMAL -> (Object) (BigDecimal) jsonValue;
            };
        } catch (ClassCastException | ArithmeticException wrongType) {
            throw new ApiException(400, "Поле '" + column.name() + "' имеет неверный тип/формат");
        }
    }
}
