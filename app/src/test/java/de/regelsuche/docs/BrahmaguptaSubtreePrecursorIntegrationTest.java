package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.docs.HistoricalPrecursorTestSupport.FrozenRule;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.transform.SubtreeHypothesisOperator;
import de.regelsuche.transform.Transformation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Characterizes the tree-local learned-rule capability needed before the
 * Brahmagupta-Fibonacci identity can be composed from independent precursors.
 *
 * <p>The frozen completion rule is still root-oriented. The matched baseline
 * receives that exact rule and therefore cannot rewrite an inner sum of squares.
 * The accumulated run wraps the same frozen identity in the generic subtree
 * adapter and must reach the unseen surrounding expression in one step.</p>
 */
class BrahmaguptaSubtreePrecursorIntegrationTest {
    private static final String SOURCE =
        "k * ((u + v)^2 + (w - 2)^2)";
    private static final String EXPECTED =
        "k * (((u + v) + (w - 2))^2 - 2*(u + v)*(w - 2))";
    private static final SearchHeuristic HEURISTIC =
        new SearchHeuristic(1, 64, 1, 4, 16, 32);

    private final ExpressionParser parser = new ExpressionParser();
    private final HistoricalPrecursorTestSupport support =
        new HistoricalPrecursorTestSupport();

    @Test
    @Timeout(180)
    void frozenCompletionRuleTransfersInsideAnUnseenSurroundingExpression() {
        RuntimeTask completionTask = support.completionTask();
        String observableTraining = completionTask.observableInput();
        assertFalse(observableTraining.contains(SOURCE));
        assertFalse(observableTraining.contains(EXPECTED));

        FrozenRule completion = support.freeze(completionTask);
        assertTrue(completion.operator()
            .generateCandidates(SOURCE)
            .isEmpty());

        SubtreeHypothesisOperator localCompletion =
            new SubtreeHypothesisOperator(completion.operator(), 8);
        Transformation localMove = localCompletion
            .generateCandidates(SOURCE)
            .stream()
            .filter(candidate -> parser.parseTerm(
                candidate.transformedExpression()).equals(
                    parser.parseTerm(EXPECTED)))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "tree-local completion was not generated"));
        assertEquals(
            completion.candidate().dynamicRuleId(),
            localMove.rule());
        assertTrue(localMove.applicationKey().startsWith(
            "subtree-v1:" + completion.candidate().dynamicRuleId() + ":"));
        assertTrue(support.exactVerifier().verify(
            localMove.transformedExpression(),
            EXPECTED).proved());

        GoalSearchResult baseline = support.search(
            support.engine(List.of(completion.operator())),
            HEURISTIC,
            SOURCE,
            localMove.transformedExpression());
        GoalSearchResult accumulated = support.search(
            support.engine(List.of(localCompletion)),
            HEURISTIC,
            SOURCE,
            localMove.transformedExpression());

        assertFalse(baseline.reached(), baseline.toString());
        assertTrue(accumulated.reached(), accumulated.toString());
        assertNotNull(accumulated.reachedState(), accumulated.toString());
        assertEquals(1, accumulated.reachedState().depth());
        assertTrue(accumulated.reachedState().appliedRuleIds().contains(
            completion.candidate().dynamicRuleId()));
        assertTrue(accumulated.reachedState().appliedRuleApplications().stream()
            .anyMatch(key -> key.startsWith("subtree-v1:")));
    }
}
