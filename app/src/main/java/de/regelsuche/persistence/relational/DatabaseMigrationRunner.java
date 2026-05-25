package de.regelsuche.persistence.relational;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Minimal Flyway-style migration runner for the optional PostgreSQL mode. */
public final class DatabaseMigrationRunner {
    public static final List<DatabaseMigration> DEFAULT_MIGRATIONS = List.of(
        new DatabaseMigration(1, "discovery_persistence", "db/migration/V1__discovery_persistence.sql"),
        new DatabaseMigration(2, "full_text_and_facets", "db/migration/V2__full_text_and_facets.sql"),
        new DatabaseMigration(3, "hibernate_search_fields", "db/migration/V3__hibernate_search_fields.sql")
    );

    private final List<DatabaseMigration> migrations;
    private final ClassLoader classLoader;

    public DatabaseMigrationRunner() {
        this(DEFAULT_MIGRATIONS, DatabaseMigrationRunner.class.getClassLoader());
    }

    public DatabaseMigrationRunner(List<DatabaseMigration> migrations, ClassLoader classLoader) {
        this.migrations = List.copyOf(migrations);
        this.classLoader = classLoader == null ? DatabaseMigrationRunner.class.getClassLoader() : classLoader;
    }

    public void migrate(Connection connection) throws SQLException, IOException {
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            ensureHistoryTable(connection);
            for (DatabaseMigration migration : migrations) {
                if (!isApplied(connection, migration.version())) {
                    executeSql(connection, readResource(migration.resourcePath()));
                    recordApplied(connection, migration);
                }
            }
            connection.commit();
        } catch (SQLException | IOException | RuntimeException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private void ensureHistoryTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS regelsuche_schema_history ("
                + "version INTEGER PRIMARY KEY, "
                + "name VARCHAR(200) NOT NULL, "
                + "applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        }
    }

    private boolean isApplied(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM regelsuche_schema_history WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void recordApplied(Connection connection, DatabaseMigration migration) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO regelsuche_schema_history(version, name) VALUES (?, ?)")) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.name());
            statement.executeUpdate();
        }
    }

    private String readResource(String resourcePath) throws IOException {
        try (InputStream input = classLoader.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("Missing migration resource: " + resourcePath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void executeSql(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String command : sql.split(";\\s*(?:\\r?\\n|$)")) {
                if (!command.isBlank()) {
                    statement.execute(command);
                }
            }
        }
    }
}
