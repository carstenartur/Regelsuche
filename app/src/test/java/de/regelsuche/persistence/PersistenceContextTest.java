package de.regelsuche.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.search.memory.TranspositionEntry;
import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistenceContextTest {

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
    void jsonFileStoreSurvivesRestart(@TempDir Path tmp) {
        DiscoveredTransformation tx = new DiscoveredTransformation(
            "demo-path-1",
            "(x+3)^2",
            "9 + 6 * x + x ^ 2",
            List.of(new TransformationStep(
                0,
                "(x+3)^2",
                "9 + 6 * x + x ^ 2",
                "demo-step",
                RewriteKind.SIMPLIFY,
                5,
                4,
                true,
                ""
            )),
            new ExpressionScore(5, 0, 0, 0, 0),
            new ExpressionScore(4, 0, 0, 0, 0),
            1,
            CandidateProofStatus.OBSERVED,
            Instant.parse("2024-01-01T00:00:00Z"),
            "hash"
        );
        try (JsonFileExpressionGraphStore store = new JsonFileExpressionGraphStore(tmp)) {
            store.saveDiscoveredTransformation(tx);
            assertEquals(1, store.discoveredTransformations().size());
            assertTrue(Files.exists(tmp.resolve(JsonFileExpressionGraphStore.STORAGE_FILE)));
        }
        // Reopen — data must survive.
        try (JsonFileExpressionGraphStore reopened = new JsonFileExpressionGraphStore(tmp)) {
            assertEquals(1, reopened.discoveredTransformations().size());
            assertEquals("demo-path-1", reopened.discoveredTransformations().get(0).id());
        }
    }

    @Test
    void jsonFileInventoryRoundTrip(@TempDir Path tmp) {
        ReusableRule rule = new ReusableRule(
            "rule-1",
            "A + 0",
            "A",
            List.of(),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.MATCHES_KNOWN_RULE,
            3,
            1.0,
            Instant.parse("2024-01-01T00:00:00Z"),
            "hash-1",
            null,
            0
        );
        try (JsonFileRuleInventoryRepository repo = new JsonFileRuleInventoryRepository(tmp)) {
            repo.save(rule);
            repo.addTag("rule-1", "algebra");
        }
        try (JsonFileRuleInventoryRepository reopened = new JsonFileRuleInventoryRepository(tmp)) {
            List<ReusableRule> all = reopened.findAll();
            assertEquals(1, all.size());
            assertEquals("rule-1", all.get(0).id());
            assertTrue(reopened.tagsOf("rule-1").contains("algebra"));
        }
    }

    @Test
    void persistenceContextHonoursJsonFileMode(@TempDir Path tmp) throws IOException {
        PersistenceConfig config = new PersistenceConfig(
            GraphPersistenceMode.JSON_FILE, tmp, null, null, null);
        try (PersistenceContext ctx = PersistenceContext.from(config, null)) {
            assertEquals(GraphPersistenceMode.JSON_FILE, ctx.effectiveMode());
            assertNotNull(ctx.graphStore());
            assertNotNull(ctx.inventoryRepository());
        }
    }

    @Test
    void jsonFileTranspositionTableSurvivesRestart(@TempDir Path tmp) {
        PersistenceConfig config = new PersistenceConfig(
            GraphPersistenceMode.JSON_FILE, tmp, null, null, null);
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        try (PersistenceContext ctx = PersistenceContext.from(config, null)) {
            ctx.transpositionTable().record(new TranspositionEntry(
                "hash-1",
                "x + 0",
                3,
                1,
                "path-1",
                Set.of("rule-1"),
                1,
                now,
                now
            ));
        }
        try (PersistenceContext reopened = PersistenceContext.from(config, null)) {
            assertTrue(reopened.transpositionTable().lookup("hash-1").isPresent());
        }
    }

    @Test
    void persistenceContextFallsBackFromRemoteNeo4jWhenCredentialsMissing(@TempDir Path tmp) {
        PersistenceConfig config = new PersistenceConfig(
            GraphPersistenceMode.REMOTE_NEO4J, tmp, null, null, null);
        try (PersistenceContext ctx = PersistenceContext.from(config, null)) {
            // Without credentials we must not try to open a Bolt connection;
            // PersistenceContext degrades to JSON_FILE.
            assertEquals(GraphPersistenceMode.JSON_FILE, ctx.effectiveMode());
        }
    }

    @Test
    void persistenceContextRoutesEmbeddedNeo4jToJsonFileFallback(@TempDir Path tmp) {
        PersistenceConfig config = new PersistenceConfig(
            GraphPersistenceMode.EMBEDDED_NEO4J, tmp, null, null, null);
        try (PersistenceContext ctx = PersistenceContext.from(config, null)) {
            assertEquals(GraphPersistenceMode.JSON_FILE, ctx.effectiveMode());
        }
    }
}
