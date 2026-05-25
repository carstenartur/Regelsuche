package de.regelsuche.persistence.relational;

import de.regelsuche.persistence.GraphPersistenceMode;
import de.regelsuche.persistence.PersistenceConfig;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

public final class PersistenceAdapterFactory {
    public static final String PERSISTENCE_UNIT = "regelsuche-postgresql";

    private PersistenceAdapterFactory() {
    }

    public static Optional<RelationalPersistenceAdapters> create(PersistenceConfig config, PrintStream log) {
        if (config.mode() != GraphPersistenceMode.POSTGRESQL
            && config.mode() != GraphPersistenceMode.POSTGRESQL_WITH_JSON_FALLBACK) {
            return Optional.empty();
        }
        if (!config.hasPostgresCredentials()) {
            if (config.mode() == GraphPersistenceMode.POSTGRESQL) {
                throw new IllegalArgumentException("POSTGRESQL mode requires POSTGRES_URL, POSTGRES_USER and POSTGRES_PASSWORD");
            }
            ensureJsonFallbackPath(config, log);
            return Optional.empty();
        }
        try (Connection connection = DriverManager.getConnection(
            config.postgresUrl(), config.postgresUser(), config.postgresPassword())) {
            new DatabaseMigrationRunner().migrate(connection);
        } catch (SQLException | IOException exception) {
            throw new IllegalStateException("Could not initialize PostgreSQL persistence at " + config.postgresUrl(), exception);
        }
        EntityManagerFactory entityManagerFactory = Persistence.createEntityManagerFactory(PERSISTENCE_UNIT, Map.of(
            "jakarta.persistence.jdbc.url", config.postgresUrl(),
            "jakarta.persistence.jdbc.user", config.postgresUser(),
            "jakarta.persistence.jdbc.password", config.postgresPassword(),
            "hibernate.search.backend.directory.root", config.storagePath().resolve("hibernate-search").toAbsolutePath().toString()
        ));
        if (log != null) {
            log.println("Persistence: Hibernate ORM/Search metadata initialized at " + config.postgresUrl());
        }
        return Optional.of(RelationalPersistenceAdapters.of(entityManagerFactory));
    }

    private static void ensureJsonFallbackPath(PersistenceConfig config, PrintStream log) {
        try {
            Files.createDirectories(config.storagePath());
        } catch (IOException exception) {
            throw new IllegalStateException("Hybrid mode requires a writable JSON fallback path: "
                + config.storagePath().toAbsolutePath(), exception);
        }
        if (log != null) {
            log.println("Persistence: hybrid PostgreSQL config incomplete; using JSON fallback at "
                + config.storagePath().toAbsolutePath());
        }
    }
}
