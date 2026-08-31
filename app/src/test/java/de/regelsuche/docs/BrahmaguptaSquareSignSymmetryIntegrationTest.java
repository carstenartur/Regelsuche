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
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.transform.AdditivePairHypothesisOperator;
import de.regelsuche.transform.SquareBaseSignSymmetryOperator;
import de.regelsuche.transform.SubtreeHypothesisOperator;
import de.regelsuche.transform.Transformation;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Shows that one independently learned square-completion rule can produce both
 * plus- and difference-centred forms after an exact representation symmetry.
 */
class BrahmaguptaSquareSignSymmetryIntegrationTest {
    private static final String SOURCE =
        "(a*c)^2 + (a*d)^2 + (b*c)^2 + (b*d)^2";
    private static final String EXPECTED_DIFFERENCE_COMPLETION =
        "((a*c) - (b*d))^2 + 2*(a*c)*(b*d)"
            + " + (a*d)^2 + (b*c)^2";
    private static final SearchHeuristic HEURISTIC =
        new SearchHeuristic(2, 512, 1, 16, 64, 128);

    private final ExpressionParser parser = new ExpressionParser();
    private final HistoricalPrecursorTestSupport support =
        new HistoricalPrecursorTestSupport();

    @Test
    @Timeout(180)
    void sameFrozenCompletionRuleProducesADifferenceCentredSquare() {
        RuntimeTask completionTask = support.completionTask();
        String observableTraining = completionTask.observableInput();
        assertFalse(observableTraining.contains(SOURCE));
        assertFalse(observableTraining.contains(
            EXPECTED_DIFFERENCE_COMPLETION));

        FrozenRule completion = support.freeze(completionTask);
        SubtreeHypothesisOperator signSymmetry =
            new SubtreeHypothesisOperator(
                new SquareBaseSignSymmetryOperator(),
                16);
        AdditivePairHypothesisOperator pairCompletion =
            new AdditivePairHypothesisOperator(
                completion.operator(),
                32);

        List<Transformation> signMoves =
            signSymmetry.generateCandidates(SOURCE);
        Transformation negateBd = signMoves.stream()
            .filter(candidate -> containsNegatedSquareBase(
                candidate.transformedExpression(),
                "b*d"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "the (b*d)^2 occurrence was not sign-reflected; candidates="
                    + signMoves));

        List<Transformation> completionMoves =
            pairCompletion.generateCandidates(
                negateBd.transformedExpression());
        Transformation differenceCompletion = completionMoves.stream()
            .filter(candidate -> containsSquareWithEquivalentBase(
                candidate.transformedExpression(),
                "a*c - b*d"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "the learned rule did not form an (a*c-b*d)^2 square; "
                    + "candidates=" + completionMoves));

        assertEquals(
            completion.candidate().dynamicRuleId(),
            differenceCompletion.rule());
        assertTrue(differenceCompletion.applicationKey().startsWith(
            "additive-pair-v1:"
                + completion.candidate().dynamicRuleId()
                + ":0.3:"));
        assertTrue(support.exactVerifier().verify(
            differenceCompletion.transformedExpression(),
            EXPECTED_DIFFERENCE_COMPLETION).proved());

        GoalSearchResult baseline = support.search(
            support.engine(List.of(signSymmetry)),
            HEURISTIC,
            SOURCE,
            differenceCompletion.transformedExpression());
        GoalSearchResult accumulated = support.search(
            support.engine(List.of(
                signSymmetry,
                pairCompletion)),
            HEURISTIC,
            SOURCE,
            differenceCompletion.transformedExpression());

        assertFalse(baseline.reached(), baseline.toString());
        assertTrue(accumulated.reached(), accumulated.toString());
        assertNotNull(accumulated.reachedState(), accumulated.toString());
        assertEquals(2, accumulated.reachedState().depth());
        assertTrue(accumulated.reachedState().appliedRuleIds().contains(
            SquareBaseSignSymmetryOperator.RULE_ID));
        assertTrue(accumulated.reachedState().appliedRuleIds().contains(
            completion.candidate().dynamicRuleId()));
        assertTrue(accumulated.reachedState()
            .appliedRuleApplications()
            .stream()
            .anyMatch(key -> key.startsWith("subtree-v1:")));
        assertTrue(accumulated.reachedState()
            .appliedRuleApplications()
            .stream()
            .anyMatch(key -> key.startsWith("additive-pair-v1:")));
    }

    private boolean containsNegatedSquareBase(
        String expression,
        String expectedBase
    ) {
        Expr base = parser.parseTerm(expectedBase);
        return contains(
            parser.parseTerm(expression),
            candidate -> candidate instanceof BinaryExpr power
                && power.operator() == BinaryOperator.POW
                && isTwo(power.right())
                && power.left() instanceof BinaryExpr subtraction
                && subtraction.operator() == BinaryOperator.SUB
                && isZero(subtraction.left())
                && subtraction.right().equals(base));
    }

    private boolean containsSquareWithEquivalentBase(
        String expression,
        String expectedBase
    ) {
        return contains(
            parser.parseTerm(expression),
            candidate -> candidate instanceof BinaryExpr power
                && power.operator() == BinaryOperator.POW
                && isTwo(power.right())
                && support.exactVerifier().verify(
                    ExpressionFormatter.format(power.left()),
                    expectedBase).proved());
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

    private static boolean isTwo(Expr expression) {
        return expression instanceof NumberExpr number
            && Double.compare(number.value(), 2.0) == 0;
    }

    private static boolean isZero(Expr expression) {
        return expression instanceof NumberExpr number
            && Double.compare(number.value(), 0.0) == 0;
    }
}
