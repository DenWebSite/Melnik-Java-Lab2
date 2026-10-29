package shop;

import java.sql.*;
import java.util.*;

/** Работа с БД через чистый JDBC: подключение, транзакции, вспомогательные запросы. */
public final class Db {
    private Db() {}

    public static final String DATABASE_URL = readSetting("DB_URL", "jdbc:postgresql://localhost:5432/shopdb");
    public static final String DATABASE_USER = readSetting("DB_USER", "postgres");
    public static final String DATABASE_PASSWORD = readSetting("DB_PASSWORD", "postgres");

    /** Читает настройку из переменной окружения; если её нет - берёт значение по умолчанию. */
    private static String readSetting(String environmentVariableName, String defaultValue) {
        String environmentValue = System.getenv(environmentVariableName);
        return (environmentValue == null || environmentValue.isBlank()) ? defaultValue : environmentValue;
    }

    /** Действие, которое выполняется с открытым соединением. */
    @FunctionalInterface
    public interface DbWork<ResultType> {
        ResultType run(Connection connection) throws SQLException;
    }

    public static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD);
    }

    /** Выполняет действие в режиме autocommit (подходит для чтения). */
    public static <ResultType> ResultType withConnection(DbWork<ResultType> work) throws SQLException {
        try (Connection connection = openConnection()) {
            return work.run(connection);
        }
    }

    /** Транзакция: BEGIN ... COMMIT, при любой ошибке - ROLLBACK. */
    public static <ResultType> ResultType inTransaction(DbWork<ResultType> work) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                ResultType result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    /** SELECT: возвращает все строки результата, каждая строка - Map "имя колонки -> значение". */
    public static List<Map<String, Object>> selectRows(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();
                List<Map<String, Object>> resultRows = new ArrayList<>();
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
                        Object columnValue = resultSet.getObject(columnIndex);
                        if (columnValue instanceof java.util.Date) columnValue = columnValue.toString(); // дата/время -> текст
                        row.put(metaData.getColumnLabel(columnIndex), columnValue);
                    }
                    resultRows.add(row);
                }
                return resultRows;
            }
        }
    }

    /** SELECT, который должен вернуть одну строку; если строк нет - возвращает null. */
    public static Map<String, Object> selectOne(Connection connection, String sql, Object... parameters)
            throws SQLException {
        List<Map<String, Object>> foundRows = selectRows(connection, sql, parameters);
        return foundRows.isEmpty() ? null : foundRows.get(0);
    }

    /** INSERT / UPDATE / DELETE: возвращает количество затронутых строк. */
    public static int executeUpdate(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, parameters);
            return statement.executeUpdate();
        }
    }

    private static void bindParameters(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int parameterIndex = 0; parameterIndex < parameters.length; parameterIndex++) {
            statement.setObject(parameterIndex + 1, parameters[parameterIndex]);
        }
    }
}
