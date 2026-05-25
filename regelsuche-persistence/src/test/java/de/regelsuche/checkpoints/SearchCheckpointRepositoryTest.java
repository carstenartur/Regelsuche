package de.regelsuche.checkpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.checkpoint.InMemorySearchCheckpointRepository;
import de.regelsuche.checkpoint.JsonFileSearchCheckpointRepository;
import de.regelsuche.checkpoint.SearchCheckpoint;
import de.regelsuche.checkpoint.SearchCheckpointRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchCheckpointRepositoryTest {

    private SearchCheckpoint sample(String jobId) {
        return new SearchCheckpoint(
            jobId,
            "(x + 1)^2",
            "FAST_SIMPLIFY",
            "default",
            List.of("x^2 + 2*x + 1", "x*(x + 2) + 1"),
            List.of("hash-a", "hash-b", "hash-c"),
            List.of(
                new SearchCheckpoint.BestPath("x^2 + 2*x + 1", 4, "rule_expand_square"),
                new SearchCheckpoint.BestPath("x*(x + 2) + 1", 3, "rule_factor")
            ),
            42L,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:05:00Z")
        );
    }

    @Test
    void checkpointResumeContinuesFrontier() {
        SearchCheckpoint cp = sample("job-1");
        // The resume seed must be the best frontier expression — *not* the
        // original input — so resume continues from progress rather than
        // restarting from scratch.
        assertNotEquals(cp.originalExpression(), cp.resumeSeed());
        assertEquals("x^2 + 2*x + 1", cp.resumeSeed());
        // Visited hashes are preserved verbatim.
        assertEquals(3, cp.visitedHashes().size());
    }

    @Test
    void inMemoryRepositoryStoresAndRetrievesByJobId() {
        SearchCheckpointRepository repo = new InMemorySearchCheckpointRepository();
        repo.save(sample("alpha"));
        repo.save(sample("beta"));
        assertEquals(2, repo.findAll().size());
        Optional<SearchCheckpoint> found = repo.findByJobId("alpha");
        assertTrue(found.isPresent());
        assertEquals("alpha", found.get().jobId());
        assertTrue(repo.delete("alpha"));
        assertFalse(repo.findByJobId("alpha").isPresent());
    }

    @Test
    void jsonFileRepositoryRoundTripsCheckpoint(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("checkpoints.json");
        JsonFileSearchCheckpointRepository writer = new JsonFileSearchCheckpointRepository(file);
        writer.save(sample("file-job"));
        assertTrue(Files.exists(file));

        // Fresh instance must rehydrate from disk.
        JsonFileSearchCheckpointRepository reader = new JsonFileSearchCheckpointRepository(file);
        Optional<SearchCheckpoint> restored = reader.findByJobId("file-job");
        assertTrue(restored.isPresent());
        SearchCheckpoint cp = restored.get();
        assertNotSame(sample("file-job"), cp);
        assertEquals("(x + 1)^2", cp.originalExpression());
        assertEquals(List.of("x^2 + 2*x + 1", "x*(x + 2) + 1"), cp.frontier());
        assertEquals(3, cp.visitedHashes().size());
        assertEquals(2, cp.bestPaths().size());
        assertEquals("x^2 + 2*x + 1", cp.bestPaths().get(0).expression());
        assertEquals(4, cp.bestPaths().get(0).improvement());
        assertEquals(42L, cp.randomSeed());
        assertNotNull(cp.createdAt());
        assertNotNull(cp.updatedAt());
    }

    @Test
    void resumeSeedFallsBackToOriginalWhenFrontierEmpty() {
        SearchCheckpoint cp = new SearchCheckpoint(
            "j", "orig", "FAST_SIMPLIFY", "default",
            List.of(), List.of(), List.of(),
            0L, Instant.now(), Instant.now()
        );
        assertEquals("orig", cp.resumeSeed());
    }

    private static void assertNotEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertNotEquals(expected, actual);
    }
}
