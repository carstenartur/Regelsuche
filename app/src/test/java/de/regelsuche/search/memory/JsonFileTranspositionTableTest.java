package de.regelsuche.search.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFileTranspositionTableTest {

    @Test
    void roundTripPersistsEntries(@TempDir Path dir) throws Exception {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        try (var first = noop()) { /* placeholder */ }
        JsonFileTranspositionTable a = new JsonFileTranspositionTable(dir);
        a.record(new TranspositionEntry(
            "h1", "(x+1)^2", 30, 2, "path-1",
            Set.of("rule_a", "rule_b"), 1, t0, t0));
        a.record(new TranspositionEntry(
            "h2", "9 + 6*x + x^2", 22, 4, "path-2",
            Set.of("rule_c"), 1, t0, t0.plusSeconds(2)));
        assertTrue(Files.exists(dir.resolve(JsonFileTranspositionTable.STORAGE_FILE)));

        // Re-open the table from the same directory.
        JsonFileTranspositionTable b = new JsonFileTranspositionTable(dir);
        assertEquals(2, b.size());
        TranspositionEntry h1 = b.lookup("h1").orElseThrow();
        assertEquals("(x+1)^2", h1.canonicalExpression());
        assertEquals(30, h1.bestScore());
        assertEquals(2, h1.minDepthSeen());
        assertEquals("path-1", h1.bestKnownPathId());
        assertTrue(h1.reachedByRuleIds().contains("rule_a"));
        assertTrue(h1.reachedByRuleIds().contains("rule_b"));
    }

    private static AutoCloseable noop() {
        return () -> { };
    }
}
