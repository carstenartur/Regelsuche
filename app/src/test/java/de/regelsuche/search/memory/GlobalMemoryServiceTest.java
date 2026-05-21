package de.regelsuche.search.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobalMemoryServiceTest {

    private final Instant now = Instant.parse("2026-05-21T18:00:00Z");

    @Test
    void universalityScoreFavorsDiverseRulePathsOverRawVisitCount() {
        TranspositionEntry highVisitsLowDiversity = new TranspositionEntry(
            "hash-a", "a + b", 5, 1, "p1",
            new LinkedHashSet<>(List.of("r1")),
            100, now.minus(Duration.ofDays(1)), now);
        TranspositionEntry lowVisitsHighDiversity = new TranspositionEntry(
            "hash-b", "b + c", 5, 1, "p2",
            new LinkedHashSet<>(List.of("r1", "r2", "r3", "r4", "r5", "r6")),
            8, now.minus(Duration.ofDays(1)), now);
        InMemoryTranspositionTable table = new InMemoryTranspositionTable();
        table.record(highVisitsLowDiversity);
        table.record(lowVisitsHighDiversity);

        GlobalMemoryService service = new GlobalMemoryService(table);
        // diversity * 5 + visits + recency outweighs raw visit dominance.
        assertTrue(service.universalityScore(lowVisitsHighDiversity, now)
            > service.universalityScore(highVisitsLowDiversity, now),
            "diverse rule paths must beat raw visit count");
    }

    @Test
    void topUniversalPatternsRespectsLimitAndOrder() {
        InMemoryTranspositionTable table = new InMemoryTranspositionTable();
        table.record(entry("h1", 1, Set.of("r1"), now));
        table.record(entry("h2", 2, Set.of("r1", "r2", "r3"), now));
        table.record(entry("h3", 5, Set.of("r1"), now));

        GlobalMemoryService service = new GlobalMemoryService(table);
        List<TranspositionEntry> top = service.topUniversalPatterns(2, now);

        assertEquals(2, top.size());
        // h2 has 3 distinct rule paths → diversity bonus 15, dominates.
        assertEquals("h2", top.get(0).canonicalHash());
    }

    @Test
    void ruleCoverageCountsDistinctStatesPerRule() {
        InMemoryTranspositionTable table = new InMemoryTranspositionTable();
        table.record(entry("h1", 1, Set.of("add_zero", "mul_one"), now));
        table.record(entry("h2", 1, Set.of("add_zero"), now));
        table.record(entry("h3", 1, Set.of("mul_one", "factor_out"), now));

        Map<String, Integer> coverage = new GlobalMemoryService(table).ruleCoverage();

        assertEquals(2, coverage.get("add_zero"));
        assertEquals(2, coverage.get("mul_one"));
        assertEquals(1, coverage.get("factor_out"));
        // Insertion order is descending count then alphabetical
        List<String> order = List.copyOf(coverage.keySet());
        assertEquals("add_zero", order.get(0));
        assertEquals("mul_one", order.get(1));
        assertEquals("factor_out", order.get(2));
    }

    @Test
    void garbageCollectDropsOnlyRareAndOldEntries() {
        InMemoryTranspositionTable table = new InMemoryTranspositionTable();
        // popular, old → keep
        table.record(entry("popular-old", 50, Set.of("r1"), now.minus(Duration.ofDays(90))));
        // rare, recent → keep
        table.record(entry("rare-fresh", 1, Set.of("r1"), now));
        // rare, old → drop
        table.record(entry("rare-old", 1, Set.of("r1"), now.minus(Duration.ofDays(90))));

        int removed = new GlobalMemoryService(table)
            .garbageCollect(5, Duration.ofDays(30), now);

        assertEquals(1, removed);
        assertTrue(table.lookup("popular-old").isPresent());
        assertTrue(table.lookup("rare-fresh").isPresent());
        assertFalse(table.lookup("rare-old").isPresent());
    }

    @Test
    void jsonBackendWritesSchemaVersionAndStaysCompatibleAfterRoundtrip(@TempDir Path tmp) throws Exception {
        JsonFileTranspositionTable table = new JsonFileTranspositionTable(tmp);
        table.record(entry("h-roundtrip", 3, Set.of("r1", "r2"), now));

        String written = Files.readString(table.filePath());
        assertTrue(written.contains("\"schemaVersion\": " + GlobalMemoryService.SCHEMA_VERSION),
            "persisted JSON must declare the schema version");

        // Restart: a fresh table reads from disk and recovers the entry.
        JsonFileTranspositionTable reopened = new JsonFileTranspositionTable(tmp);
        assertTrue(reopened.lookup("h-roundtrip").isPresent());
    }

    @Test
    void garbageCollectIsPersistentForJsonBackend(@TempDir Path tmp) {
        JsonFileTranspositionTable table = new JsonFileTranspositionTable(tmp);
        table.record(entry("rare-old", 1, Set.of("r1"), now.minus(Duration.ofDays(90))));
        new GlobalMemoryService(table).garbageCollect(5, Duration.ofDays(30), now);

        // Restart and confirm the entry has not come back.
        JsonFileTranspositionTable reopened = new JsonFileTranspositionTable(tmp);
        assertFalse(reopened.lookup("rare-old").isPresent());
    }

    private static TranspositionEntry entry(String hash, int visits, Set<String> ruleIds, Instant lastSeen) {
        return new TranspositionEntry(
            hash, hash, 5, 1, hash + "-path",
            new LinkedHashSet<>(ruleIds),
            visits, lastSeen.minus(Duration.ofDays(1)), lastSeen);
    }
}
