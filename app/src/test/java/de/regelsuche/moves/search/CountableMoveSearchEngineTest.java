package de.regelsuche.moves.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.MoveCandidateTransformationEngine;
import de.regelsuche.moves.RewriteMoveDeriver;
import de.regelsuche.moves.RewriteMoveKind;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class CountableMoveSearchEngineTest {
    private final CountableMoveSearchEngine engine = new CountableMoveSearchEngine();

    @Test
    void findsCompleteSquarePathWithinBoundedSearch() {
        CountableMoveSearchEngine.CountableMoveSearchResult result =
            engine.search("x^2 + 6*x + 5", "(x + 3)^2 - 4", 4, 120);

        assertTrue(result.success());
        assertTrue(result.pathLength() >= 1);
        assertEquals(result.pathLength(), result.appliedMoves().size());
        assertEquals(result.pathLength(), result.appliedRuleIds().size());
        assertTrue(result.appliedMoves().stream().allMatch(move -> move.kind() != RewriteMoveKind.UNKNOWN));
    }

    @Test
    void returnsDeterministicResultAcrossRuns() {
        CountableMoveSearchEngine.CountableMoveSearchResult first =
            engine.search("x*(y+1)+z*(y+1)", "(y+1)*(x+z)", 4, 120);
        CountableMoveSearchEngine.CountableMoveSearchResult second =
            engine.search("x*(y+1)+z*(y+1)", "(y+1)*(x+z)", 4, 120);

        assertEquals(first.success(), second.success());
        assertEquals(first.pathExpressions(), second.pathExpressions());
        assertEquals(first.appliedRuleIds(), second.appliedRuleIds());
        assertEquals(first.appliedMoves().stream().map(move -> move.moveId()).toList(),
            second.appliedMoves().stream().map(move -> move.moveId()).toList());
        assertEquals(first.exploredStateCount(), second.exploredStateCount());
        assertEquals(first.uniqueCanonicalStateCount(), second.uniqueCanonicalStateCount());
    }

    @Test
    void enforcesStateBudgetWithFailureReason() {
        CountableMoveSearchEngine.CountableMoveSearchResult result =
            engine.search("x - 1 = 0", "x = 1", 4, 1);

        assertFalse(result.success());
        assertTrue(result.failureReason() == CountableMoveSearchEngine.FailureReason.MAX_STATES_REACHED
            || result.failureReason() == CountableMoveSearchEngine.FailureReason.TARGET_NOT_REACHED);
        assertTrue(result.exploredStateCount() <= 1);
    }

    @Test
    void reportsDeterministicSearchSpaceMetrics() {
        CountableMoveSearchEngine.CountableMoveSearchResult first =
            engine.search("x^2 + 6*x + 5", "(x + 3)^2 - 4", 4, 120);
        CountableMoveSearchEngine.CountableMoveSearchResult second =
            engine.search("x^2 + 6*x + 5", "(x + 3)^2 - 4", 4, 120);

        assertEquals(first.searchSpaceMetrics(), second.searchSpaceMetrics());
    }

    @Test
    void countsDuplicateCanonicalStates() {
        CountableMoveSearchEngine.CountableMoveSearchResult result =
            engine.search("x^2 + 6*x + 5", "(x + 3)^2 - 4", 4, 120);

        CountableMoveSearchEngine.SearchSpaceMetrics metrics = result.searchSpaceMetrics();
        assertTrue(metrics.generatedMoveCount() >= metrics.duplicateStateCount());
        assertTrue(metrics.duplicateStateCount() >= 1);
        assertEquals(
            metrics.generatedMoveCount(),
            metrics.moveKindHistogram().values().stream().mapToInt(Integer::intValue).sum()
        );
    }

    @Test
    void makesStateBudgetAbortVisibleInMetrics() {
        CountableMoveSearchEngine.CountableMoveSearchResult result =
            engine.search("x - 1 = 0", "x = 1", 4, 1);

        assertFalse(result.success());
        assertEquals(CountableMoveSearchEngine.FailureReason.MAX_STATES_REACHED, result.failureReason());
        assertTrue(result.searchSpaceMetrics().prunedByStateBudgetCount() > 0);
    }

    @Test
    void exposesSearchSpaceMetricsForSuccessfulPath() {
        CountableMoveSearchEngine.CountableMoveSearchResult result =
            engine.search("x^2 + 6*x + 5", "(x + 3)^2 - 4", 4, 120);

        assertTrue(result.success());
        CountableMoveSearchEngine.SearchSpaceMetrics metrics = result.searchSpaceMetrics();
        assertTrue(metrics.generatedMoveCount() >= 1);
        assertFalse(metrics.branchingFactorByDepth().isEmpty());
        assertEquals(
            result.appliedMoves().stream().map(move -> move.kind().name()).toList(),
            metrics.successfulPathMoveKinds()
        );
    }

    @Test
    void countsRootAsExploredWhenInputAlreadyMatchesTarget() {
        CountableMoveSearchEngine.CountableMoveSearchResult result = engine.search("x+1", "x+1", 4, 120);

        assertTrue(result.success());
        assertEquals(1, result.exploredStateCount());
        assertEquals(1, result.uniqueCanonicalStateCount());
        assertEquals(1, result.searchSpaceMetrics().exploredStateCount());
    }

    @Test
    void supportsConfiguredDepthAboveLegacyDepthFour() {
        CountableMoveSearchEngine customEngine = chainSearchEngine();

        CountableMoveSearchEngine.CountableMoveSearchResult legacy = customEngine.search("s0", "s5", 4, 50);
        CountableMoveSearchEngine.CountableMoveSearchResult configured = customEngine.search(
            "s0",
            "s5",
            SearchConfiguration.defaults()
        );

        assertFalse(legacy.success());
        assertTrue(configured.success());
        assertEquals(5, configured.pathLength());
    }

    @Test
    void limitsGeneratedMovesPerNodeViaConfiguration() {
        TransformationEngine branchingEngine = expression -> switch (expression) {
            case "s0" -> List.of(
                new Transformation("a", "dead"),
                new Transformation("b", "s1")
            );
            case "s1" -> List.of(new Transformation("c", "goal"));
            default -> List.of();
        };
        CountableMoveSearchEngine customEngine = new CountableMoveSearchEngine(
            new MoveCandidateTransformationEngine(branchingEngine, new Depth1MoveEnumerator(List.of())),
            new ExpressionCanonicalizer(),
            new ExpressionScorer(),
            new RewriteMoveDeriver()
        );

        CountableMoveSearchEngine.CountableMoveSearchResult limited = customEngine.search(
            "s0",
            "goal",
            new SearchConfiguration(new MoveSearchOptions(8, 12, 100, 1, 24, 32, true, true, false))
        );
        CountableMoveSearchEngine.CountableMoveSearchResult unrestricted = customEngine.search(
            "s0",
            "goal",
            new SearchConfiguration(new MoveSearchOptions(8, 12, 100, 2, 24, 32, true, true, false))
        );

        assertFalse(limited.success());
        assertTrue(unrestricted.success());
    }

    private CountableMoveSearchEngine chainSearchEngine() {
        TransformationEngine chainEngine = expression -> switch (expression) {
            case "s0" -> List.of(new Transformation("r1", "s1"));
            case "s1" -> List.of(new Transformation("r2", "s2"));
            case "s2" -> List.of(new Transformation("r3", "s3"));
            case "s3" -> List.of(new Transformation("r4", "s4"));
            case "s4" -> List.of(new Transformation("r5", "s5"));
            default -> List.of();
        };
        return new CountableMoveSearchEngine(
            new MoveCandidateTransformationEngine(chainEngine, new Depth1MoveEnumerator(List.of())),
            new ExpressionCanonicalizer(),
            new ExpressionScorer(),
            new RewriteMoveDeriver()
        );
    }
}
