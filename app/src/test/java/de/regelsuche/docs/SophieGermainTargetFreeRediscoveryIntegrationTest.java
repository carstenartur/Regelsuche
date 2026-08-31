package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.docs.HistoricalPrecursorTestSupport.FrozenRule;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.ExactMonomialSquareExposureOperator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Target-free historical rediscovery after independently freezing generic
 * precursor rules. The historical endpoint is used only for post-hoc
 * evaluation and is never attached to either search problem.
 */
class SophieGermainTargetFreeRediscoveryIntegrationTest {
    private static final String SOURCE = "x^4 + 4*y^4";
    private static final String HISTORICAL_FACTORIZATION =
        "(x^2 + 2*y^2 - 2*x*y) * (x^2 + 2*y^2 + 2*x*y)";
    private static final String REVERSED_HISTORICAL_FACTORIZATION =
        "(x^2 + 2*y^2 + 2*x*y) * (x^2 + 2*y^2 - 2*x*y)";
    private static final SearchHeuristic SEARCH_BUDGET =
        new SearchHeuristic(5, 256, 1, 8, 32, 64);

    private final HistoricalPrecursorTestSupport support =
        new HistoricalPrecursorTestSupport();
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    @Timeout(180)
    void frozenPrecursorsGenerateTheHistoricalFactorizationWithoutATarget() {
        assertTrue(matchesHistoricalFactorizationForm(
            HISTORICAL_FACTORIZATION));
        assertTrue(matchesHistoricalFactorizationForm(
            REVERSED_HISTORICAL_FACTORIZATION));
        assertFalse(matchesHistoricalFactorizationForm(SOURCE));

        RuntimeTask completionTask = support.completionTask();
        RuntimeTask differenceTask = support.differenceTask();
        String trainingMaterial = completionTask.observableInput()
            + "\n" + differenceTask.observableInput();
        assertFalse(trainingMaterial.contains(SOURCE));
        assertFalse(trainingMaterial.contains(HISTORICAL_FACTORIZATION));

        FrozenRule completion = support.freeze(completionTask);
        FrozenRule difference = support.freeze(differenceTask);
        ExactMonomialSquareExposureOperator exposure =
            new ExactMonomialSquareExposureOperator();

        GoalSearchResult baseline = support.searchUntargeted(
            support.engine(List.of(exposure)),
            SEARCH_BUDGET,
            SOURCE,
            TransformationGoal.FACTORIZE);
        GoalSearchResult accumulated = support.searchUntargeted(
            support.engine(List.of(
                exposure,
                completion.operator(),
                difference.operator())),
            SEARCH_BUDGET,
            SOURCE,
            TransformationGoal.FACTORIZE);

        assertUntargeted(baseline);
        assertUntargeted(accumulated);
        assertTrue(
            accumulated.states().size()
                < SEARCH_BUDGET.maxVisitedExpressions(),
            "rediscovery must not depend on exhausting the state budget: "
                + accumulated);
        assertFalse(containsHistoricalFactorization(baseline.states()),
            baseline.toString());

        SearchState discovered = historicalFactorizationIn(
            accumulated.states());
        assertNotNull(discovered);
        assertEquals(
            parser.parseTerm(SOURCE),
            parser.parseTerm(discovered.path().getFirst()),
            discovered.toString());
        assertTrue(support.exactVerifier().verify(
            discovered.expression(),
            HISTORICAL_FACTORIZATION).proved());
        assertTrue(discovered.appliedRuleIds().contains(
            completion.candidate().dynamicRuleId()),
            discovered.toString());
        assertTrue(discovered.appliedRuleIds().contains(
            difference.candidate().dynamicRuleId()),
            discovered.toString());
        assertTrue(discovered.appliedRuleIds().stream()
            .filter(ExactMonomialSquareExposureOperator.RULE_ID::equals)
            .count() >= 3,
            discovered.toString());
        assertEquals(5, discovered.depth(), discovered.toString());
    }

    private void assertUntargeted(GoalSearchResult result) {
        assertEquals(GoalStatus.UNTARGETED, result.status(), result.toString());
        assertNull(result.reachedState(), result.toString());
    }

    private boolean containsHistoricalFactorization(List<SearchState> states) {
        return states.stream()
            .map(SearchState::expression)
            .anyMatch(this::matchesHistoricalFactorizationForm);
    }

    private SearchState historicalFactorizationIn(List<SearchState> states) {
        return states.stream()
            .filter(state -> matchesHistoricalFactorizationForm(
                state.expression()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "historical factorization not generated by untargeted search; "
                    + "states=" + states));
    }

    private boolean matchesHistoricalFactorizationForm(String expression) {
        Expr expected = parser.parseTerm(HISTORICAL_FACTORIZATION);
        Expr actual = parser.parseTerm(expression);
        if (actual.equals(expected)) {
            return true;
        }
        if (actual instanceof BinaryExpr actualProduct
                && expected instanceof BinaryExpr expectedProduct
                && actualProduct.operator() == BinaryOperator.MUL
                && expectedProduct.operator() == BinaryOperator.MUL) {
            return actualProduct.left().equals(expectedProduct.right())
                && actualProduct.right().equals(expectedProduct.left());
        }
        return false;
    }
}
