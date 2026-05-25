package de.regelsuche.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PersistenceConfigTest {

    @Test
    void persistenceConfigFromEnvDefaultsToInMemory() {
        PersistenceConfig config = PersistenceConfig.fromEnvironment(Map.of());
        assertEquals(GraphPersistenceMode.IN_MEMORY, config.mode());
    }

    @Test
    void persistenceConfigPicksUpExplicitJsonFileMode() {
        PersistenceConfig config = PersistenceConfig.fromEnvironment(Map.of(
            PersistenceConfig.ENV_MODE, "JSON_FILE",
            PersistenceConfig.ENV_PATH, "/tmp/regelsuche-test"
        ));
        assertEquals(GraphPersistenceMode.JSON_FILE, config.mode());
        assertEquals(Path.of("/tmp/regelsuche-test"), config.storagePath());
    }

    @Test
    void persistenceConfigAutoSelectsRemoteNeo4jWhenAllCredentialsPresent() {
        PersistenceConfig config = PersistenceConfig.fromEnvironment(Map.of(
            PersistenceConfig.ENV_NEO4J_URI, "bolt://example:7687",
            PersistenceConfig.ENV_NEO4J_USER, "neo4j",
            PersistenceConfig.ENV_NEO4J_PASSWORD, "secret"
        ));
        assertEquals(GraphPersistenceMode.REMOTE_NEO4J, config.mode());
        assertTrue(config.hasNeo4jCredentials());
    }

    @Test
    void persistenceConfigDoesNotPickRemoteIfCredentialsIncomplete() {
        PersistenceConfig config = PersistenceConfig.fromEnvironment(Map.of(
            PersistenceConfig.ENV_NEO4J_URI, "bolt://example:7687"
        ));
        assertEquals(GraphPersistenceMode.IN_MEMORY, config.mode());
        assertFalse(config.hasNeo4jCredentials());
    }

    @Test
    void persistenceConfigAutoSelectsHybridPostgresWhenAllCredentialsPresent() {
        PersistenceConfig config = PersistenceConfig.fromEnvironment(Map.of(
            PersistenceConfig.ENV_POSTGRES_URL, "jdbc:postgresql://localhost:5432/regelsuche",
            PersistenceConfig.ENV_POSTGRES_USER, "regelsuche",
            PersistenceConfig.ENV_POSTGRES_PASSWORD, "secret"
        ));
        assertEquals(GraphPersistenceMode.POSTGRESQL_WITH_JSON_FALLBACK, config.mode());
        assertTrue(config.hasPostgresCredentials());
        assertFalse(config.hasNeo4jCredentials());
    }

    @Test
    void explicitPostgresModeWinsOverNeo4jAutoDetection() {
        PersistenceConfig config = PersistenceConfig.fromEnvironment(Map.of(
            PersistenceConfig.ENV_MODE, "POSTGRESQL",
            PersistenceConfig.ENV_NEO4J_URI, "bolt://example:7687",
            PersistenceConfig.ENV_NEO4J_USER, "neo4j",
            PersistenceConfig.ENV_NEO4J_PASSWORD, "secret"
        ));
        assertEquals(GraphPersistenceMode.POSTGRESQL, config.mode());
        assertFalse(config.hasPostgresCredentials());
    }
}
