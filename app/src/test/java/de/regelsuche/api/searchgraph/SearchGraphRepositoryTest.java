package de.regelsuche.api.searchgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.api.IdentityReportDto;
import de.regelsuche.api.PathReplayDto;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.transform.RewriteKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchGraphRepositoryTest {

    @Test
    void inMemoryRepositoryRoundTrip() {
        InMemorySearchGraphRepository repo = new InMemorySearchGraphRepository();
        SearchGraphRecord record = sampleRecord("session-1");
        repo.save(record);

        assertEquals(1, repo.findAll().size());
        assertEquals("session-1", repo.findById("session-1").orElseThrow().id());

        repo.delete("session-1");
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void codecRoundTripsAllFields() {
        SearchGraphRecord original = sampleRecord("session-codec");
        String json = SearchGraphRecordCodec.toJson(original);
        SearchGraphRecord parsed = SearchGraphRecordCodec.fromJson(json);

        assertEquals(original.id(), parsed.id());
        assertEquals(original.createdAt(), parsed.createdAt());
        assertEquals(original.searchProfile(), parsed.searchProfile());
        assertEquals(original.domains(), parsed.domains());
        assertEquals(original.graph().nodes().size(), parsed.graph().nodes().size());
        assertEquals(original.graph().edges().size(), parsed.graph().edges().size());
        assertEquals(original.graph().clusters().size(), parsed.graph().clusters().size());
        assertEquals(original.replays().size(), parsed.replays().size());
        assertEquals(original.identities().size(), parsed.identities().size());
        assertEquals(original.exports().get("markdown"), parsed.exports().get("markdown"));
        assertEquals(
            original.graph().clusters().get(0).type(),
            parsed.graph().clusters().get(0).type()
        );
    }

    @Test
    void jsonFileRepositorySurvivesReload(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("search-graphs.json");
        JsonFileSearchGraphRepository repo = new JsonFileSearchGraphRepository(file);
        repo.save(sampleRecord("session-a"));
        repo.save(sampleRecord("session-b"));
        assertTrue(Files.exists(file));

        // Re-create the repository to force a load from disk.
        JsonFileSearchGraphRepository reloaded = new JsonFileSearchGraphRepository(file);
        assertEquals(2, reloaded.findAll().size());
        assertNotNull(reloaded.findById("session-a").orElseThrow());
        assertNotNull(reloaded.findById("session-b").orElseThrow());

        reloaded.delete("session-a");
        JsonFileSearchGraphRepository again = new JsonFileSearchGraphRepository(file);
        assertEquals(1, again.findAll().size());
        assertFalse(again.findById("session-a").isPresent());
    }

    @Test
    void codecRoundTripsStage3ReplayDiffPayload() {
        // Build a replay step with explicit comparator-flip and diff spans
        // and assert all three fields survive a JSON round-trip.
        PathReplayDto.ReplayStep step = new PathReplayDto.ReplayStep(
            0, "x<3", "x<3", "-x>-3", "-x>-3",
            "inequality_multiply_both_sides", "expl", -2, true,
            true,
            List.of(new int[] { 0, 1 }, new int[] { 2, 1 }),
            List.of(new int[] { 1, 1 })
        );
        PathReplayDto replay = new PathReplayDto("path-flip", List.of(step));
        Map<String, Integer> rules = new LinkedHashMap<>();
        SearchGraphStatsDto stats = new SearchGraphStatsDto(
            0, 0, 0, 0, 0.0, 0, rules, List.of(), 0, 0
        );
        SearchGraphRecord record = new SearchGraphRecord(
            "session-stage3",
            Instant.parse("2026-02-02T00:00:00Z"),
            "DISCOVERY",
            List.of(),
            new SearchGraphDto(List.of(), List.of(), List.of(), stats),
            List.of(replay),
            List.of(),
            List.of(),
            Map.of()
        );

        String json = SearchGraphRecordCodec.toJson(record);
        SearchGraphRecord parsed = SearchGraphRecordCodec.fromJson(json);

        PathReplayDto.ReplayStep round = parsed.replays().get(0).steps().get(0);
        assertTrue(round.comparatorFlipped(),
            "comparatorFlipped must round-trip");
        assertEquals(2, round.changedFromSpans().size(),
            "changedFromSpans must round-trip");
        assertEquals(1, round.changedToSpans().size(),
            "changedToSpans must round-trip");
        assertEquals(0, round.changedFromSpans().get(0)[0]);
        assertEquals(1, round.changedFromSpans().get(0)[1]);
        assertEquals(2, round.changedFromSpans().get(1)[0]);
        assertEquals(1, round.changedToSpans().get(0)[0]);
    }

    private static SearchGraphRecord sampleRecord(String id) {
        SearchGraphNodeDto node = new SearchGraphNodeDto(
            "x", "x", "x", 3, 0, 1, false, false,
            CandidateProofStatus.OBSERVED, "cluster:demo"
        );
        SearchGraphNodeDto node2 = new SearchGraphNodeDto(
            "y", "y", "y", 2, 1, 1, true, false,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED, "cluster:demo"
        );
        SearchGraphEdgeDto edge = new SearchGraphEdgeDto(
            "x", "y", "rule-a", RewriteKind.SIMPLIFY, -1,
            List.of("a != 0"), List.of("success:x->y"), true
        );
        SearchGraphClusterDto cluster = new SearchGraphClusterDto(
            "cluster:demo", "demo cluster",
            ClusterType.MACRO_SEQUENCE,
            List.of("x", "y"),
            List.of("path-1"),
            0.75
        );
        Map<String, Integer> rules = new LinkedHashMap<>();
        rules.put("rule-a", 1);
        SearchGraphStatsDto stats = new SearchGraphStatsDto(
            2, 1, 0, 2, 1.0, 1, rules, List.of("rule-a"), 0, 0
        );
        SearchGraphDto graph = new SearchGraphDto(List.of(node, node2), List.of(edge), List.of(cluster), stats);

        PathReplayDto replay = new PathReplayDto("path-1", List.of(
            new PathReplayDto.ReplayStep(0, "x", "x", "y", "y", "rule-a", "explain", -1, true)
        ));
        IdentityReportDto identity = new IdentityReportDto(
            "id-1", "x", "y", List.of("rule-a"), 2, 1.0,
            CandidateProofStatus.OBSERVED, RuleStatus.NEW, List.of("path-1")
        );
        return new SearchGraphRecord(
            id,
            Instant.parse("2024-01-02T03:04:05Z"),
            "DISCOVERY",
            List.of("core", "polynomial"),
            graph,
            List.of(replay),
            List.of(),
            List.of(identity),
            Map.of("markdown", "# demo")
        );
    }
}
