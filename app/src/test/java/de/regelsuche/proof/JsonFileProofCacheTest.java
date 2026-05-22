package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.CandidateProofStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFileProofCacheTest {

    @Test
    void proofCacheSurvivesRestart(@TempDir Path tempDir) throws IOException {
        Path cacheFile = tempDir.resolve("cache.json");

        JsonFileProofCache cache1 = new JsonFileProofCache(cacheFile);
        ProofCacheKey key = ProofCacheKey.of("A+0", "A", List.of(), "lean4-v1");
        cache1.putEntry(key, new ProofCacheEntry(
            CandidateProofStatus.FORMALLY_PROVED,
            "abc.lean",
            Instant.parse("2025-01-01T00:00:00Z"),
            "unsat",
            123L
        ));
        assertEquals(1, cache1.size());

        JsonFileProofCache cache2 = new JsonFileProofCache(cacheFile);
        assertEquals(1, cache2.size(), "cache should reload from disk");
        var reloaded = cache2.getEntry(key);
        assertTrue(reloaded.isPresent());
        assertEquals(CandidateProofStatus.FORMALLY_PROVED, reloaded.get().status());
        assertEquals("abc.lean", reloaded.get().artifactId());
        assertEquals(123L, reloaded.get().durationMillis());
        assertEquals("unsat", reloaded.get().outputDigest());
    }

    @Test
    void proofCacheSeparatesDifferentAssumptions(@TempDir Path tempDir) throws IOException {
        Path cacheFile = tempDir.resolve("cache.json");
        JsonFileProofCache cache = new JsonFileProofCache(cacheFile);

        ProofCacheKey withAssumption = ProofCacheKey.of(
            "x/y", "x/y", List.of(Assumption.nonZero("y")), "lean4-v1");
        ProofCacheKey withoutAssumption = ProofCacheKey.of(
            "x/y", "x/y", List.of(), "lean4-v1");

        cache.put(withAssumption, CandidateProofStatus.FORMALLY_PROVED);
        assertTrue(cache.get(withAssumption).isPresent());
        assertFalse(cache.get(withoutAssumption).isPresent(),
            "different assumption sets must produce different cache entries");
    }

    @Test
    void proofCacheSeparatesDifferentProverVersions(@TempDir Path tempDir) throws IOException {
        Path cacheFile = tempDir.resolve("cache.json");
        JsonFileProofCache cache = new JsonFileProofCache(cacheFile);

        ProofCacheKey v1 = ProofCacheKey.of("A*B", "B*A", List.of(), "lean4-v1");
        ProofCacheKey v2 = ProofCacheKey.of("A*B", "B*A", List.of(), "lean4-v2");

        cache.put(v1, CandidateProofStatus.FORMALLY_PROVED);
        assertTrue(cache.get(v1).isPresent());
        assertFalse(cache.get(v2).isPresent(),
            "different prover versions must not share cached results");
    }

    @Test
    void belowThresholdResultsAreNotPersisted(@TempDir Path tempDir) throws IOException {
        Path cacheFile = tempDir.resolve("cache.json");
        JsonFileProofCache cache = new JsonFileProofCache(cacheFile);
        ProofCacheKey key = ProofCacheKey.of("x", "x", List.of(), "lean4");
        cache.put(key, CandidateProofStatus.VALIDATED_BY_EXAMPLES);
        assertEquals(0, cache.size());

        // Reload and confirm no spurious entry was persisted
        JsonFileProofCache reloaded = new JsonFileProofCache(cacheFile);
        assertEquals(0, reloaded.size());
    }
}
