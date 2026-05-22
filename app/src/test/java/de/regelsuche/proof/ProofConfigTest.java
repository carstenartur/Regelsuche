package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProofConfigTest {

    @Test
    void defaultsAreEnabledAndDerivedFromPersistenceRoot() {
        Path base = Paths.get("/var/lib/regelsuche");
        ProofConfig config = ProofConfig.fromEnvironment(Map.of(), base);
        assertTrue(config.enabled());
        assertEquals(base.resolve("proofs"), config.artifactPath());
        assertEquals(base.resolve("proof-jobs.json"), config.jobStorePath());
        assertEquals(base.resolve("proof-cache.json"), config.cachePath());
    }

    @Test
    void envOverridesDefaults() {
        Map<String, String> env = Map.of(
            ProofConfig.ENV_ENABLED, "false",
            ProofConfig.ENV_ARTIFACT_PATH, "/tmp/p/art",
            ProofConfig.ENV_JOB_STORE, "/tmp/p/jobs.json",
            ProofConfig.ENV_CACHE, "/tmp/p/cache.json"
        );
        ProofConfig config = ProofConfig.fromEnvironment(env, Paths.get("/ignored"));
        assertFalse(config.enabled());
        assertEquals(Paths.get("/tmp/p/art"), config.artifactPath());
        assertEquals(Paths.get("/tmp/p/jobs.json"), config.jobStorePath());
        assertEquals(Paths.get("/tmp/p/cache.json"), config.cachePath());
    }

    @Test
    void booleanParsingAcceptsTruthyAndFalsyAliases() {
        Path base = Paths.get("/tmp/x");
        assertTrue(ProofConfig.fromEnvironment(
            Map.of(ProofConfig.ENV_ENABLED, "yes"), base).enabled());
        assertTrue(ProofConfig.fromEnvironment(
            Map.of(ProofConfig.ENV_ENABLED, "1"), base).enabled());
        assertFalse(ProofConfig.fromEnvironment(
            Map.of(ProofConfig.ENV_ENABLED, "off"), base).enabled());
        assertFalse(ProofConfig.fromEnvironment(
            Map.of(ProofConfig.ENV_ENABLED, "no"), base).enabled());
    }

    @Test
    void disabledFactoryProducesPlaceholderPaths() {
        ProofConfig config = ProofConfig.disabled();
        assertFalse(config.enabled());
        // Paths are non-null but otherwise opaque
        assertTrue(config.artifactPath().toString().contains("proofs"));
    }
}
