package de.regelsuche.persistence.relational;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DatabaseMigrationRunnerTest {

    @Test
    void defaultMigrationsAreVersionedAndPackaged() {
        List<DatabaseMigration> migrations = DatabaseMigrationRunner.DEFAULT_MIGRATIONS;

        assertEquals(
            List.of(1, 2, 3, 4, 5),
            migrations.stream().map(DatabaseMigration::version).toList());
        for (DatabaseMigration migration : migrations) {
            assertNotNull(
                DatabaseMigrationRunner.class.getClassLoader()
                    .getResource(migration.resourcePath()),
                () -> "missing " + migration.resourcePath());
        }
    }

    @Test
    void migrateSkipsAppliedVersionsCommitsAndRestoresAutoCommit()
            throws Exception {
        List<DatabaseMigration> migrations = List.of(
            new DatabaseMigration(1, "first", "migration/first.sql"),
            new DatabaseMigration(2, "second", "migration/second.sql"));
        ClassLoader resources = resources(Map.of(
            "migration/first.sql", "THIS MUST NOT EXECUTE;\n",
            "migration/second.sql",
            "CREATE TABLE beta(id int);\nINSERT INTO beta VALUES (2);\n"));
        FakeJdbc jdbc = new FakeJdbc(true, Set.of(1), null);

        new DatabaseMigrationRunner(migrations, resources)
            .migrate(jdbc.connection());

        assertEquals(List.of(false, true), jdbc.autoCommitTransitions);
        assertTrue(jdbc.autoCommit);
        assertEquals(1, jdbc.commitCount);
        assertEquals(0, jdbc.rollbackCount);
        assertEquals(Set.of(1, 2), jdbc.appliedVersions);
        assertEquals(Map.of(2, "second"), jdbc.recordedNames);
        assertTrue(jdbc.executedSql.stream().anyMatch(sql ->
            sql.startsWith("CREATE TABLE IF NOT EXISTS regelsuche_schema_history")));
        assertTrue(jdbc.executedSql.contains("CREATE TABLE beta(id int)"));
        assertTrue(jdbc.executedSql.contains("INSERT INTO beta VALUES (2)"));
        assertFalse(jdbc.executedSql.stream().anyMatch(sql ->
            sql.contains("THIS MUST NOT EXECUTE")));
    }

    @Test
    void sqlFailureRollsBackAndRestoresAutoCommit() {
        DatabaseMigration migration = new DatabaseMigration(
            1, "broken", "migration/broken.sql");
        FakeJdbc jdbc = new FakeJdbc(true, Set.of(), "BROKEN");
        DatabaseMigrationRunner runner = new DatabaseMigrationRunner(
            List.of(migration),
            resources(Map.of("migration/broken.sql", "BROKEN;\n")));

        SQLException failure = assertThrows(
            SQLException.class,
            () -> runner.migrate(jdbc.connection()));

        assertTrue(failure.getMessage().contains("BROKEN"));
        assertEquals(0, jdbc.commitCount);
        assertEquals(1, jdbc.rollbackCount);
        assertEquals(List.of(false, true), jdbc.autoCommitTransitions);
        assertTrue(jdbc.autoCommit);
        assertTrue(jdbc.recordedNames.isEmpty());
    }

    @Test
    void missingResourceRollsBackAndRestoresAutoCommit() {
        DatabaseMigration migration = new DatabaseMigration(
            1, "missing", "migration/missing.sql");
        FakeJdbc jdbc = new FakeJdbc(true, Set.of(), null);
        DatabaseMigrationRunner runner = new DatabaseMigrationRunner(
            List.of(migration), resources(Map.of()));

        IOException failure = assertThrows(
            IOException.class,
            () -> runner.migrate(jdbc.connection()));

        assertTrue(failure.getMessage().contains("migration/missing.sql"));
        assertEquals(0, jdbc.commitCount);
        assertEquals(1, jdbc.rollbackCount);
        assertEquals(List.of(false, true), jdbc.autoCommitTransitions);
        assertTrue(jdbc.autoCommit);
    }

    @Test
    void nullConnectionIsRejectedBeforeAnyMigrationWork() {
        DatabaseMigrationRunner runner = new DatabaseMigrationRunner(
            List.of(), resources(Map.of()));

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> runner.migrate(null));

        assertEquals("connection must not be null", failure.getMessage());
    }

    private static ClassLoader resources(Map<String, String> resources) {
        return new ClassLoader(DatabaseMigrationRunnerTest.class.getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                String content = resources.get(name);
                return content == null
                    ? null
                    : new ByteArrayInputStream(content.getBytes(UTF_8));
            }
        };
    }

    private static final class FakeJdbc {
        private boolean autoCommit;
        private final List<Boolean> autoCommitTransitions = new ArrayList<>();
        private final Set<Integer> appliedVersions = new LinkedHashSet<>();
        private final Map<Integer, String> recordedNames = new LinkedHashMap<>();
        private final List<String> executedSql = new ArrayList<>();
        private final String failingSqlToken;
        private int commitCount;
        private int rollbackCount;

        private FakeJdbc(
            boolean autoCommit,
            Set<Integer> appliedVersions,
            String failingSqlToken
        ) {
            this.autoCommit = autoCommit;
            this.appliedVersions.addAll(appliedVersions);
            this.failingSqlToken = failingSqlToken;
        }

        private Connection connection() {
            return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> {
                    autoCommit = (Boolean) args[0];
                    autoCommitTransitions.add(autoCommit);
                    yield null;
                }
                case "createStatement" -> statement();
                case "prepareStatement" -> preparedStatement((String) args[0]);
                case "commit" -> {
                    commitCount++;
                    yield null;
                }
                case "rollback" -> {
                    rollbackCount++;
                    yield null;
                }
                case "close" -> null;
                case "isClosed" -> false;
                case "toString" -> "FakeJdbcConnection";
                default -> defaultValue(method.getReturnType());
            });
        }

        private Statement statement() {
            return proxy(Statement.class, (proxy, method, args) -> switch (method.getName()) {
                case "execute" -> {
                    String sql = ((String) args[0]).trim();
                    if (failingSqlToken != null && sql.contains(failingSqlToken)) {
                        throw new SQLException("forced SQL failure for " + failingSqlToken);
                    }
                    executedSql.add(sql);
                    yield false;
                }
                case "close" -> null;
                case "toString" -> "FakeJdbcStatement";
                default -> defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement preparedStatement(String sql) {
            int[] intParameter = {0};
            String[] stringParameter = {null};
            return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
                case "setInt" -> {
                    intParameter[0] = (Integer) args[1];
                    yield null;
                }
                case "setString" -> {
                    stringParameter[0] = (String) args[1];
                    yield null;
                }
                case "executeQuery" -> {
                    if (!sql.startsWith("SELECT 1 FROM regelsuche_schema_history")) {
                        throw new SQLException("unexpected query: " + sql);
                    }
                    yield resultSet(appliedVersions.contains(intParameter[0]));
                }
                case "executeUpdate" -> {
                    if (!sql.startsWith("INSERT INTO regelsuche_schema_history")) {
                        throw new SQLException("unexpected update: " + sql);
                    }
                    appliedVersions.add(intParameter[0]);
                    recordedNames.put(intParameter[0], stringParameter[0]);
                    yield 1;
                }
                case "close" -> null;
                case "toString" -> "FakeJdbcPreparedStatement";
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSet resultSet(boolean rowPresent) {
            boolean[] first = {true};
            return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
                case "next" -> {
                    boolean result = first[0] && rowPresent;
                    first[0] = false;
                    yield result;
                }
                case "close" -> null;
                case "toString" -> "FakeJdbcResultSet";
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
