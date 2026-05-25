package de.regelsuche.dockere2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.persistence.relational.DatabaseMigrationRunner;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.wait.strategy.Wait;

@Testcontainers(disabledWithoutDocker = true)
class PostgresDiscoveryPersistenceTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:16-alpine")
        .withEnv("POSTGRES_DB", "regelsuche")
        .withEnv("POSTGRES_USER", "regelsuche")
        .withEnv("POSTGRES_PASSWORD", "regelsuche-demo")
        .withExposedPorts(5432)
        .waitingFor(Wait.forListeningPort());

    @Test
    void migrationsCreateSearchableDiscoverySchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(
            "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/regelsuche",
            "regelsuche",
            "regelsuche-demo")) {
            new DatabaseMigrationRunner().migrate(connection);
            new DatabaseMigrationRunner().migrate(connection);

            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO search_index_documents(document_id, type, entity_id, title, body, facets)
                VALUES ('RULE:rule-1', 'RULE', 'rule-1', 'Quadratic expansion', 'Expand polynomial squares', '{"domain":"polynomial"}'::jsonb)
                ON CONFLICT (type, entity_id) DO NOTHING
                """)) {
                assertEquals(1, insert.executeUpdate());
            }

            try (PreparedStatement query = connection.prepareStatement("""
                SELECT entity_id
                FROM search_index_documents
                WHERE search_vector @@ plainto_tsquery('simple', ?)
                  AND facets @> ?::jsonb
                """)) {
                query.setString(1, "expansion");
                query.setString(2, "{\"domain\":\"polynomial\"}");
                try (ResultSet resultSet = query.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("rule-1", resultSet.getString(1));
                }
            }
        }
    }
}
