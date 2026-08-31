package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.docs.HistoricalPrecursorTestSupport.FrozenRule;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.transform.AdditivePairHypothesisOperator;
import de.regelsuche.transform.Transformation;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Isolates the associative pair-selection capability required before learned
 * square-completion rules can compose the Brahmagupta-Fibonacci identity.
 */
class BrahmaguptaAdditivePairPrecursorIntegrationTest {
    private static final String SOURCE =
        "(a*c)^2 + (a*d)^2 + (b*c)^2 + (b*d)^2";
    private static final String EXPECTED_PAIR_COMPLETION =
        "((a*c) + (b*d))^2 - 2*(a*c)*(b*d)"
            + " + (a*d)^2 + (b*c)^2";
    private static final SearchHeuristic HEURISTIC =
        new SearchHeuristic(1, 128, 1, 8, 32, 64);

    private final ExpressionParser parser = new ExpressionParser();
    private final HistoricalPrecursorTestSupport support =
        new HistoricalPrecursorTestSupport();

    @Test
    @Timeout(180)
    void frozenCompletionRuleTransfersToANonAdjacentPair() {
        RuntimeTask completionTask = support.completionTask();
        String observableTraining = completionTask.observableInput();
        assertFalse(observableTraining.contains(SOURCE));
        assertFalse(observableTraining.contains(
            EXPECTED_PAIR_COMPLETION));

        FrozenRule completion = support.freeze(completionTask);
        assertTrue(completion.operator()
            .generateCandidates(SOURCE)
            .isEmpty());

        AdditivePairHypothesisOperator pairCompletion =
            new AdditivePairHypothesisOperator(
                completion.operator(),
                16);
        List<Transformation> pairMoves =
            pairCompletion.generateCandidates(SOURCE);
        Transformation nonAdjacent = pairMoves.stream()
            .filter(candidate -> containsSquareOfSum(
                candidate.transformedExpression(),
                "a*c",
                "b*d"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "learned completion did not select the non-adjacent "
                    + "(a*c)^2 and (b*d)^2 terms; candidates="
                    + pairMoves));

        assertEquals(
            completion.candidate().dynamicRuleId(),
            nonAdjacent.rule());
        assertTrue(nonAdjacent.applicationKey().startsWith(
            "additive-pair-v1:"
                + completion.candidate().dynamicRuleId()
                + ":0.3:"));
        assertTrue(support.exactVerifier().verify(
            nonAdjacent.transformedExpression(),
            EXPECTED_PAIR_COMPLETION).proved());

        GoalSearchResult baseline = support.search(
            support.engine(List.of(completion.operator())),
            HEURISTIC,
            SOURCE,
            nonAdjacent.transformedExpression());
        GoalSearchResult accumulated = support.search(
            support.engine(List.of(pairCompletion)),
            HEURISTIC,
            SOURCE,
            nonAdjacent.transformedExpression());

        assertFalse(baseline.reached(), baseline.toString());
        assertTrue(accumulated.reached(), accumulated.toString());
        assertNotNull(accumulated.reachedState(), accumulated.toString());
        assertEquals(1, accumulated.reachedState().depth());
        assertTrue(accumulated.reachedState().appliedRuleIds().contains(
            completion.candidate().dynamicRuleId()));
        assertTrue(accumulated.reachedState()
            .appliedRuleApplications()
            .stream()
            .anyMatch(key -> key.startsWith("additive-pair-v1:")));
    }

    private boolean containsSquareOfSum(
        String expression,
        String leftBase,
        String rightBase
    ) {
        Expr left = parser.parseTerm(leftBase);
        Expr right = parser.parseTerm(rightBase);
        return contains(
            parser.parseTerm(expression),
            candidate -> candidate instanceof BinaryExpr power
                && power.operator() == BinaryOperator.POW
                && power.right() instanceof NumberExpr exponent
                && Double.compare(exponent.value(), 2.0) == 0
                && power.left() instanceof BinaryExpr sum
                && sum.operator() == BinaryOperator.ADD
                && ((sum.left().equals(left)
                        && sum.right().equals(right))
                    || (sum.left().equals(right)
                        && sum.right().equals(left))));
    }

    private boolean contains(Expr expression, Predicate<Expr> predicate) {
        if (predicate.test(expression)) {
            return true;
        }
        if (expression instanceof BinaryExpr binary) {
            return contains(binary.left(), predicate)
                || contains(binary.right(), predicate);
        }
        if (expression instanceof FunctionExpr function) {
            return function.arguments().stream()
                .anyMatch(argument -> contains(argument, predicate));
        }
        return false;
    }
}
