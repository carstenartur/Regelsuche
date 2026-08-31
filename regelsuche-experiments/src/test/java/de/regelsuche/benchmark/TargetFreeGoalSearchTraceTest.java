package de.regelsuche.benchmark;

import static de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus.REACHED;
import static de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus.UNTARGETED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.TargetFreeGoalSearchTrace.Content;
import de.regelsuche.benchmark.TargetFreeGoalSearchTrace.Trace;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.RewriteKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetFreeGoalSearchTraceTest {
    private static final SearchHeuristic HEURISTIC =
        new SearchHeuristic(3, 100, 1, 4, 16, 32);
    private static final List<String> INVENTORY = List.of(
        "canonicalize",
        "pair");
    private static final List<String> LEARNED_RULES = List.of(
        "learned-completion");

    @Test
    void freezesCompleteLineageDeterministicallyWithoutHistoricalData() {
        Trace first = trace(result("application-a", UNTARGETED, false));
        Trace second = trace(result("application-a", UNTARGETED, false));

        assertEquals(first, second);
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(first, Trace.fromCanonicalJson(first.toCanonicalJson()));
        assertEquals(3, first.content().states().size());
        assertEquals(
            List.of("a + b", "b + a", "(b + a) + 0"),
            first.content().states().get(2).path());
        assertEquals(
            List.of("commute", "add-zero"),
            first.content().states().get(2).appliedRuleIds());
        assertEquals(
            List.of("application-a", "application-z"),
            first.content().states().get(2)
                .appliedRuleApplications());
        assertEquals(
            first.content().states().get(2).fingerprint(),
            first.content().bestStateFingerprint());
        assertTrue(first.toCanonicalJson().contains(
            "\"terminalStatus\":\"UNTARGETED\""));
        assertTrue(first.toCanonicalJson().contains(
            "\"claimBoundary\":"));
    }

    @Test
    void retainsExactCanonicalBytesUnderTheContentHash(
        @TempDir Path temporaryDirectory
    ) throws Exception {
        Trace trace = trace(result("application-a", UNTARGETED, false));

        var first = TargetFreeGoalSearchTrace.retain(
            temporaryDirectory,
            trace);
        var second = TargetFreeGoalSearchTrace.retain(
            temporaryDirectory,
            trace);
        byte[] retained = Files.readAllBytes(first.path());

        assertEquals(first, second);
        assertEquals(trace, first.trace());
        assertEquals(trace.toCanonicalJson(), new String(
            retained,
            StandardCharsets.UTF_8));
        assertEquals(
            trace.contentHash().substring("sha256:".length()) + ".json",
            first.path().getFileName().toString());
        assertEquals(retained.length, first.byteLength());
        assertTrue(first.byteHash().startsWith("sha256:"));
        assertEquals(trace, Trace.fromCanonicalBytes(retained));
    }

    @Test
    void contentHashBindsApplicationIdentityInventoryAndLearnedRules() {
        Trace original = trace(result(
            "application-a",
            UNTARGETED,
            false));
        Trace changedApplication = trace(result(
            "application-b",
            UNTARGETED,
            false));
        Trace changedInventory = TargetFreeGoalSearchTrace.freeze(
            "a + b",
            TransformationGoal.PROOF_FRIENDLY,
            HEURISTIC,
            List.of("canonicalize", "pair", "sign"),
            LEARNED_RULES,
            result("application-a", UNTARGETED, false));
        Trace changedLearnedRule = TargetFreeGoalSearchTrace.freeze(
            "a + b",
            TransformationGoal.PROOF_FRIENDLY,
            HEURISTIC,
            INVENTORY,
            List.of("other-learned-rule"),
            result("application-a", UNTARGETED, false));

        assertNotEquals(
            original.contentHash(),
            changedApplication.contentHash());
        assertNotEquals(
            original.contentHash(),
            changedInventory.contentHash());
        assertNotEquals(
            original.contentHash(),
            changedLearnedRule.contentHash());
    }

    @Test
    void rejectsTargetedReachedOrDistanceBearingResults() {
        GoalSearchResult reached = result(
            "application-a",
            REACHED,
            true);
        GoalSearchResult distanceBearing = new GoalSearchResult(
            reached.states(),
            null,
            reached.bestState(),
            4,
            UNTARGETED,
            reached.metrics());

        assertThrows(
            IllegalArgumentException.class,
            () -> trace(reached));
        assertThrows(
            IllegalArgumentException.class,
            () -> trace(distanceBearing));
    }

    @Test
    void rejectsChangedContentWithAnOldHash() {
        Trace original = trace(result(
            "application-a",
            UNTARGETED,
            false));
        Content changed = new Content(
            original.content().sourceExpression(),
            "OTHER_OBJECTIVE",
            original.content().strategy(),
            original.content().budget(),
            original.content().operatorInventory(),
            original.content().frozenLearnedRuleIds(),
            original.content().terminalStatus(),
            original.content().bestDistance(),
            original.content().bestStateFingerprint(),
            original.content().metrics(),
            original.content().states(),
            original.content().claimBoundary());

        assertThrows(
            IllegalArgumentException.class,
            () -> new Trace(
                original.schema(),
                changed,
                original.contentHash()));
    }

    @Test
    void canonicalDecoderRejectsUnknownTrailingAndTamperedData() {
        Trace trace = trace(result(
            "application-a",
            UNTARGETED,
            false));
        String canonical = trace.toCanonicalJson();

        assertThrows(
            IllegalArgumentException.class,
            () -> Trace.fromCanonicalJson(canonical + "\n"));
        assertThrows(
            IllegalArgumentException.class,
            () -> Trace.fromCanonicalJson(canonical.replaceFirst(
                "\\{",
                "{\"unexpected\":true,")));
        assertThrows(
            IllegalArgumentException.class,
            () -> Trace.fromCanonicalJson(canonical.replace(
                "(b + a) + 0",
                "tampered-expression")));
    }

    private Trace trace(GoalSearchResult result) {
        return TargetFreeGoalSearchTrace.freeze(
            "a + b",
            TransformationGoal.PROOF_FRIENDLY,
            HEURISTIC,
            INVENTORY,
            LEARNED_RULES,
            result);
    }

    private GoalSearchResult result(
        String secondApplicationKey,
        de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus status,
        boolean reached
    ) {
        SearchState root = new SearchState(
            "a + b",
            0,
            new ExpressionScore(5, 3, 1, 1, 0),
            List.of("a + b"),
            List.of(),
            Set.of(),
            0,
            "value-v1:root",
            null,
            null,
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            0,
            List.of(),
            List.of(),
            List.of());
        SearchState child = new SearchState(
            "b + a",
            1,
            new ExpressionScore(5, 3, 1, 1, 0),
            List.of("a + b", "b + a"),
            List.of("commute"),
            Set.of("application-z"),
            0,
            "value-v1:child",
            "a + b",
            "commute",
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            0,
            List.of(RewriteKind.NORMALIZE),
            List.of(true),
            List.of());
        SearchState grandchild = new SearchState(
            "(b + a) + 0",
            2,
            new ExpressionScore(11, 5, 2, 2, 0),
            List.of("a + b", "b + a", "(b + a) + 0"),
            List.of("commute", "add-zero"),
            Set.of("application-z", secondApplicationKey),
            1,
            "value-v1:grandchild",
            "b + a",
            "add-zero",
            RewriteKind.EXPAND,
            true,
            2,
            true,
            -6,
            List.of(RewriteKind.NORMALIZE, RewriteKind.EXPAND),
            List.of(true, true),
            List.of("x != 0"));
        GoalMetrics metrics = new GoalMetrics(
            3,
            2,
            2,
            2,
            0,
            0,
            0,
            1,
            0,
            0,
            0,
            3,
            3,
            3);
        return new GoalSearchResult(
            List.of(root, child, grandchild),
            reached ? grandchild : null,
            grandchild,
            reached ? 0 : -1,
            status,
            metrics);
    }
}
