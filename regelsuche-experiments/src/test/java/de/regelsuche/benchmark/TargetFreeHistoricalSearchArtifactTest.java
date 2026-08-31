package de.regelsuche.benchmark;

import static de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus.REACHED;
import static de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus.UNTARGETED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.TargetFreeHistoricalSearchArtifact.Comparison;
import de.regelsuche.benchmark.TargetFreeHistoricalSearchArtifact.RunInput;
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

class TargetFreeHistoricalSearchArtifactTest {
    private static final SearchHeuristic HEURISTIC =
        new SearchHeuristic(3, 100, 1, 4, 16, 32);

    @Test
    void freezesAllStateLineageDeterministicallyAndWritesExactBytes(
        @TempDir Path temporaryDirectory
    ) throws Exception {
        Comparison first = comparison("application-z");
        Comparison second = comparison("application-z");

        assertEquals(first, second);
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertTrue(first.contentHash().startsWith("sha256:"));
        assertEquals(2, first.baseline().states().size());
        assertEquals(
            List.of("application-z"),
            first.baseline().states().get(1)
                .appliedRuleApplications());
        assertEquals(
            List.of("a + b", "b + a"),
            first.baseline().states().get(1).path());
        assertEquals(
            List.of("commute"),
            first.baseline().states().get(1).appliedRuleIds());

        var verified = TargetFreeHistoricalSearchArtifact.write(
            temporaryDirectory,
            first);
        String retained = Files.readString(
            verified.artifactPath(),
            StandardCharsets.UTF_8);

        assertEquals(first.toCanonicalJson() + "\n", retained);
        assertEquals(first, verified.comparison());
        assertEquals(
            retained.getBytes(StandardCharsets.UTF_8).length,
            verified.byteLength());
        assertTrue(verified.byteHash().startsWith("sha256:"));
    }

    @Test
    void contentHashBindsApplicationIdentityAndOperatorInventory() {
        Comparison first = comparison("application-a");
        Comparison changedAction = comparison("application-b");
        Comparison changedInventory = freeze(
            result("application-a", UNTARGETED, false),
            List.of("canonicalize", "pair", "sign"));

        assertNotEquals(
            first.contentHash(),
            changedAction.contentHash());
        assertNotEquals(
            first.contentHash(),
            changedInventory.contentHash());
    }

    @Test
    void rejectsTargetedOrReachedResults() {
        GoalSearchResult reached = result(
            "application-a",
            REACHED,
            true);

        assertThrows(
            IllegalArgumentException.class,
            () -> new RunInput(List.of("canonicalize"), reached));
    }

    @Test
    void comparisonRejectsAChangedPayloadWithAnOldHash() {
        Comparison original = comparison("application-a");

        assertThrows(
            IllegalArgumentException.class,
            () -> new Comparison(
                original.schema(),
                original.studyId(),
                "changed source",
                original.objective(),
                original.strategy(),
                original.budget(),
                original.frozenLearnedRuleId(),
                original.baseline(),
                original.accumulated(),
                original.claimBoundary(),
                original.contentHash()));
    }

    private Comparison comparison(String applicationKey) {
        return freeze(
            result(applicationKey, UNTARGETED, false),
            List.of("canonicalize", "pair"));
    }

    private Comparison freeze(
        GoalSearchResult result,
        List<String> accumulatedInventory
    ) {
        return TargetFreeHistoricalSearchArtifact.freeze(
            "target-free-test-v1",
            "a + b",
            TransformationGoal.PROOF_FRIENDLY,
            HEURISTIC,
            "learned-completion",
            new RunInput(
                List.of("canonicalize"),
                result),
            new RunInput(
                accumulatedInventory,
                result));
    }

    private GoalSearchResult result(
        String applicationKey,
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
            Set.of(applicationKey),
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
        GoalMetrics metrics = new GoalMetrics(
            2,
            1,
            1,
            1,
            0,
            0,
            0,
            1,
            0,
            0,
            0,
            2,
            2,
            2);
        return new GoalSearchResult(
            List.of(root, child),
            reached ? child : null,
            child,
            reached ? 0 : -1,
            status,
            metrics);
    }
}
