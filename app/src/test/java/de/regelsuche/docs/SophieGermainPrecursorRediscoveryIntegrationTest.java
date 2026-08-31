package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.docs.HistoricalPrecursorTestSupport.FrozenRule;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.transform.ExactMonomialSquareExposureOperator;
import de.regelsuche.transform.Transformation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Bounded historical transfer from two independently learned precursor rules.
 * The final endpoint is used only to measure reachability; theorem formation is
 * not yet target-blind in this test.
 */
class SophieGermainPrecursorRediscoveryIntegrationTest {
    private static final String SOURCE = "x^4 + 4*y^4";
    private static final String HISTORICAL_FACTORIZATION =
        "(x^2 + 2*y^2 - 2*x*y) * (x^2 + 2*y^2 + 2*x*y)";
    private static final SearchHeuristic FINAL_HEURISTIC =
        new SearchHeuristic(5, 4_000, 1, 16, 96, 256);

    private final HistoricalPrecursorTestSupport support =
        new HistoricalPrecursorTestSupport();

    @Test
    @Timeout(180)
    void independentFrozenPrecursorsComposeOnTheUnseenHistoricalHoldout() {
        RuntimeTask completionTask = support.completionTask();
        RuntimeTask differenceTask = support.differenceTask();
        String trainingMaterial = completionTask.observableInput()
            + "\n" + differenceTask.observableInput();
        assertFalse(trainingMaterial.contains(SOURCE));
        assertFalse(trainingMaterial.contains(HISTORICAL_FACTORIZATION));

        FrozenRule completion = support.freeze(completionTask);
        FrozenRule difference = support.freeze(differenceTask);
        assertFalse(completion.candidate().leftPattern().contains("^4"));
        assertFalse(difference.candidate().leftPattern().contains("^4"));
        assertFalse(completion.operator().ruleId().equals(
            difference.operator().ruleId()));
        verifyIndependentHoldouts(completion, difference);

        ExactMonomialSquareExposureOperator exposure =
            new ExactMonomialSquareExposureOperator();
        String leftExposed = support.requireExactMove(
            exposure,
            SOURCE,
            "(x^2)^2 + 4*y^4");
        String bothExposed = support.requireExactMove(
            exposure,
            leftExposed,
            "(x^2)^2 + (2*y^2)^2");
        Transformation completionMove = support.onlyApplication(
            completion.operator(),
            bothExposed);
        Transformation crossExposure = exposure
            .generateCandidates(completionMove.transformedExpression())
            .stream()
            .filter(candidate -> !difference.operator()
                .generateCandidates(candidate.transformedExpression())
                .isEmpty())
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no exact square exposure made the learned difference rule "
                    + "applicable to "
                    + completionMove.transformedExpression()));
        Transformation factorMove = support.onlyApplication(
            difference.operator(),
            crossExposure.transformedExpression());
        String discoveredTarget = factorMove.transformedExpression();

        var identity = support.exactVerifier().verify(
            discoveredTarget,
            HISTORICAL_FACTORIZATION);
        assertTrue(identity.proved(),
            discoveredTarget + " != " + HISTORICAL_FACTORIZATION);

        GoalSearchResult baseline = support.search(
            support.engine(List.of(exposure)),
            FINAL_HEURISTIC,
            SOURCE,
            discoveredTarget);
        GoalSearchResult accumulated = support.search(
            support.engine(List.of(
                exposure,
                completion.operator(),
                difference.operator())),
            FINAL_HEURISTIC,
            SOURCE,
            discoveredTarget);

        assertFalse(baseline.reached(), baseline.toString());
        assertTrue(accumulated.reached(), accumulated.toString());
        assertNotNull(accumulated.reachedState(), accumulated.toString());
        assertTrue(accumulated.reachedState().appliedRuleIds().contains(
            completion.candidate().dynamicRuleId()));
        assertTrue(accumulated.reachedState().appliedRuleIds().contains(
            difference.candidate().dynamicRuleId()));
        assertTrue(accumulated.reachedState().appliedRuleIds().stream()
            .filter(ExactMonomialSquareExposureOperator.RULE_ID::equals)
            .count() >= 3,
            accumulated.reachedState().toString());
        assertEquals(5, accumulated.reachedState().depth());
    }

    private void verifyIndependentHoldouts(
        FrozenRule completion,
        FrozenRule difference
    ) {
        Transformation completionHoldout = support.onlyApplication(
            completion.operator(),
            "(m + 1)^2 + n^2");
        assertTrue(support.exactVerifier().verify(
            completionHoldout.transformedExpression(),
            "((m + 1) + n)^2 - 2*(m + 1)*n").proved());
        assertTrue(completion.operator()
            .generateCandidates("m^3 + n^2")
            .isEmpty());

        Transformation differenceHoldout = support.onlyApplication(
            difference.operator(),
            "(m + 1)^2 - n^2");
        assertTrue(support.exactVerifier().verify(
            differenceHoldout.transformedExpression(),
            "((m + 1) - n) * ((m + 1) + n)").proved());
        assertTrue(difference.operator()
            .generateCandidates("m^2 + n^2")
            .isEmpty());
    }
}
