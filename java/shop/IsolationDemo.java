package shop;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Задание 2.2: демонстрация аномалий на разных уровнях изоляции PostgreSQL.
 * Две независимые сессии (A и B) управляются из одного потока в строго заданном порядке.
 * Используется служебная таблица isolation_demo.
 */
public final class IsolationDemo {
    private IsolationDemo() {}

    public static final List<String> LEVEL_NAMES =
            List.of("READ_UNCOMMITTED", "READ_COMMITTED", "REPEATABLE_READ", "SERIALIZABLE");
    public static final List<String> ANOMALY_NAMES =
            List.of("dirty_read", "non_repeatable_read", "phantom_read", "lost_update", "write_skew");

    private static int toJdbcIsolationLevel(String levelName) {
        return switch (levelName) {
            case "READ_UNCOMMITTED" -> Connection.TRANSACTION_READ_UNCOMMITTED;
            case "READ_COMMITTED" -> Connection.TRANSACTION_READ_COMMITTED;
            case "REPEATABLE_READ" -> Connection.TRANSACTION_REPEATABLE_READ;
            case "SERIALIZABLE" -> Connection.TRANSACTION_SERIALIZABLE;
            default -> throw new ApiException(400, "Неизвестный уровень. Допустимы: " + LEVEL_NAMES);
        };
    }

    /** Открывает соединение и начинает транзакцию с заданным уровнем изоляции. */
    private static Connection beginTransaction(int isolationLevel) throws SQLException {
        Connection session = Db.openConnection();
        session.setAutoCommit(false);
        session.setTransactionIsolation(isolationLevel);
        return session;
    }

    private static int readValueOfRow(Connection session, int rowId) throws SQLException {
        Map<String, Object> row = Db.selectOne(session, "SELECT value FROM isolation_demo WHERE id = ?", rowId);
        return ((Number) row.get("value")).intValue();
    }

    private static int readSumOfAllValues(Connection session) throws SQLException {
        Map<String, Object> row = Db.selectOne(session, "SELECT COALESCE(SUM(value),0) AS total FROM isolation_demo");
        return ((Number) row.get("total")).intValue();
    }

    private static int countRowsWithLargeValue(Connection session) throws SQLException {
        Map<String, Object> row = Db.selectOne(session,
                "SELECT COUNT(*) AS rows_count FROM isolation_demo WHERE value >= 50");
        return ((Number) row.get("rows_count")).intValue();
    }

    private static void updateValueOfRow(Connection session, int rowId, int newValue) throws SQLException {
        Db.executeUpdate(session, "UPDATE isolation_demo SET value = ? WHERE id = ?", newValue, rowId);
    }

    /** Очищает служебную таблицу и заполняет её начальными данными под конкретный сценарий. */
    private static void resetDemoTable(String anomalyName) throws SQLException {
        try (Connection connection = Db.openConnection()) {
            Db.executeUpdate(connection, "DELETE FROM isolation_demo");
            switch (anomalyName) {
                case "write_skew" -> {
                    Db.executeUpdate(connection, "INSERT INTO isolation_demo VALUES (1, 1, 'дежурный 1')");
                    Db.executeUpdate(connection, "INSERT INTO isolation_demo VALUES (2, 1, 'дежурный 2')");
                }
                case "phantom_read" -> {
                    Db.executeUpdate(connection, "INSERT INTO isolation_demo VALUES (1, 100, 'a')");
                    Db.executeUpdate(connection, "INSERT INTO isolation_demo VALUES (2, 100, 'b')");
                }
                default -> Db.executeUpdate(connection, "INSERT INTO isolation_demo VALUES (1, 100, 'a')");
            }
        }
    }

    private static String describeError(SQLException sqlException) {
        return "SQLSTATE " + sqlException.getSQLState() + ": " + sqlException.getMessage();
    }

    /** Запускает один сценарий на одном уровне изоляции. */
    public static Map<String, Object> runScenario(String anomalyName, String levelName) throws SQLException {
        if (!ANOMALY_NAMES.contains(anomalyName))
            throw new ApiException(400, "Неизвестная аномалия. Допустимы: " + ANOMALY_NAMES);
        int isolationLevel = toJdbcIsolationLevel(levelName);
        resetDemoTable(anomalyName);

        List<String> steps = new ArrayList<>();   // пошаговый журнал сценария
        String errorMessage = null;               // ошибка, которую вернула СУБД (если была)
        boolean anomalyOccurred = false;

        switch (anomalyName) {
            case "dirty_read" -> {
                try (Connection sessionA = beginTransaction(isolationLevel);
                     Connection sessionB = beginTransaction(Connection.TRANSACTION_READ_COMMITTED)) {
                    updateValueOfRow(sessionB, 1, 999);
                    steps.add("B: UPDATE value = 999 (БЕЗ COMMIT)");
                    int valueSeenByA = readValueOfRow(sessionA, 1);
                    steps.add("A: SELECT value -> " + valueSeenByA);
                    sessionB.rollback();
                    steps.add("B: ROLLBACK");
                    sessionA.rollback();
                    anomalyOccurred = valueSeenByA == 999;
                }
            }
            case "non_repeatable_read" -> {
                try (Connection sessionA = beginTransaction(isolationLevel)) {
                    int firstReadByA = readValueOfRow(sessionA, 1);
                    steps.add("A: SELECT value -> " + firstReadByA);
                    try (Connection sessionB = Db.openConnection()) {
                        updateValueOfRow(sessionB, 1, 200);
                        steps.add("B: UPDATE value = 200; COMMIT");
                    }
                    int secondReadByA = readValueOfRow(sessionA, 1);
                    steps.add("A: повторный SELECT value -> " + secondReadByA);
                    sessionA.commit();
                    anomalyOccurred = firstReadByA != secondReadByA;
                }
            }
            case "phantom_read" -> {
                try (Connection sessionA = beginTransaction(isolationLevel)) {
                    int firstCountByA = countRowsWithLargeValue(sessionA);
                    steps.add("A: SELECT COUNT(*) WHERE value >= 50 -> " + firstCountByA);
                    try (Connection sessionB = Db.openConnection()) {
                        Db.executeUpdate(sessionB, "INSERT INTO isolation_demo VALUES (3, 100, 'phantom')");
                        steps.add("B: INSERT новой строки (value = 100); COMMIT");
                    }
                    int secondCountByA = countRowsWithLargeValue(sessionA);
                    steps.add("A: повторный COUNT(*) -> " + secondCountByA);
                    sessionA.commit();
                    anomalyOccurred = firstCountByA != secondCountByA;
                }
            }
            case "lost_update" -> {
                try (Connection sessionA = beginTransaction(isolationLevel);
                     Connection sessionB = beginTransaction(isolationLevel)) {
                    int valueReadByA = readValueOfRow(sessionA, 1);
                    int valueReadByB = readValueOfRow(sessionB, 1);
                    steps.add("A: SELECT value -> " + valueReadByA + "; B: SELECT value -> " + valueReadByB);

                    int newValueFromA = valueReadByA + 10;
                    updateValueOfRow(sessionA, 1, newValueFromA);
                    sessionA.commit();
                    steps.add("A: UPDATE value = " + newValueFromA + " (прочитанное + 10); COMMIT");

                    int newValueFromB = valueReadByB + 20;
                    try {
                        updateValueOfRow(sessionB, 1, newValueFromB);
                        sessionB.commit();
                        steps.add("B: UPDATE value = " + newValueFromB + " (прочитанное + 20); COMMIT - успешно");
                    } catch (SQLException sqlException) {
                        errorMessage = describeError(sqlException);
                        steps.add("B: UPDATE отклонён -> " + errorMessage);
                        sessionB.rollback();
                    }
                }
                try (Connection checkSession = Db.openConnection()) {
                    int finalValue = readValueOfRow(checkSession, 1);
                    steps.add("Итог: value = " + finalValue + " (ожидалось 130 = 100 + 10 + 20)");
                    anomalyOccurred = errorMessage == null && finalValue != 130;
                }
            }
            case "write_skew" -> {
                // Инвариант: хотя бы один "дежурный" должен остаться (SUM(value) >= 1)
                try (Connection sessionA = beginTransaction(isolationLevel);
                     Connection sessionB = beginTransaction(isolationLevel)) {
                    int sumSeenByA = readSumOfAllValues(sessionA);
                    int sumSeenByB = readSumOfAllValues(sessionB);
                    steps.add("A: SUM(value) -> " + sumSeenByA + "; B: SUM(value) -> " + sumSeenByB
                            + " (инвариант: сумма >= 1)");
                    try {
                        if (sumSeenByA >= 2) updateValueOfRow(sessionA, 1, 0);
                        steps.add("A: UPDATE id=1 SET value = 0");
                        if (sumSeenByB >= 2) updateValueOfRow(sessionB, 2, 0);
                        steps.add("B: UPDATE id=2 SET value = 0");
                    } catch (SQLException sqlException) {
                        errorMessage = describeError(sqlException);
                    }
                    try {
                        sessionA.commit();
                        steps.add("A: COMMIT - успешно");
                    } catch (SQLException sqlException) {
                        errorMessage = describeError(sqlException);
                        steps.add("A: COMMIT отклонён -> " + errorMessage);
                        sessionA.rollback();
                    }
                    try {
                        sessionB.commit();
                        steps.add("B: COMMIT - успешно");
                    } catch (SQLException sqlException) {
                        errorMessage = describeError(sqlException);
                        steps.add("B: COMMIT отклонён -> " + errorMessage);
                        sessionB.rollback();
                    }
                }
                try (Connection checkSession = Db.openConnection()) {
                    int finalSum = readSumOfAllValues(checkSession);
                    steps.add("Итог: SUM(value) = " + finalSum
                            + (finalSum == 0 ? " (инвариант нарушен!)" : " (инвариант сохранён)"));
                    anomalyOccurred = finalSum == 0;
                }
            }
            default -> throw new ApiException(400, "Неизвестная аномалия");
        }

        Map<String, Object> scenarioResult = new LinkedHashMap<>();
        scenarioResult.put("anomaly", anomalyName);
        scenarioResult.put("level", levelName);
        scenarioResult.put("occurred", anomalyOccurred);
        scenarioResult.put("error", errorMessage);
        scenarioResult.put("steps", steps);
        return scenarioResult;
    }

    /** Полная матрица: все аномалии x все уровни. */
    public static Map<String, Object> runAllScenarios() throws SQLException {
        Map<String, Map<String, Object>> verdictsByAnomaly = new LinkedHashMap<>();
        List<Map<String, Object>> allScenarioDetails = new ArrayList<>();
        for (String anomalyName : ANOMALY_NAMES) {
            Map<String, Object> verdictsByLevel = new LinkedHashMap<>();
            for (String levelName : LEVEL_NAMES) {
                Map<String, Object> scenarioResult = runScenario(anomalyName, levelName);
                boolean anomalyOccurred = (Boolean) scenarioResult.get("occurred");
                verdictsByLevel.put(levelName, anomalyOccurred ? "ВОЗНИКАЕТ" : "не возникает");
                allScenarioDetails.add(scenarioResult);
            }
            verdictsByAnomaly.put(anomalyName, verdictsByLevel);
        }
        Map<String, Object> fullReport = new LinkedHashMap<>();
        fullReport.put("matrix", verdictsByAnomaly);
        fullReport.put("details", allScenarioDetails);
        return fullReport;
    }
}
