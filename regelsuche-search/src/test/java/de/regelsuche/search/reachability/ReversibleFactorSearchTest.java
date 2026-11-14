package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.AssumptionSignature;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReversibleFactorSearchTest {
    private static final ReversibleFactorSearch SEARCH = new ReversibleFactorSearch();
    private static final AssumptionSignature CONTEXT = AssumptionSignature.ofExpressions(List.of("0 != x"));
    private static final String SOURCE = "pair(a*x+a*y,b*x+b*y)";
    private static final String TARGET = "pair(a*(x+y),b*(x+y))";

    @Test void iterativeDeepeningChargesEarlierIterationsAndNeverUsesTheReverseFrontier() {
        var mode = ReversibleFactorSearch.Mode.ITERATIVE_DEEPENING;
        var empty = SEARCH.search("x", "y", CONTEXT, Integer.MAX_VALUE, 1, mode);
        assertEquals(ReversibleFactorSearch.Status.NO_PATH_WITHIN_DEPTH, empty.status());
        assertEquals(1, empty.work().totalCalls());
        var shallow = SEARCH.search(SOURCE, TARGET, CONTEXT, 1, 1000, mode);
        var reached = SEARCH.search(SOURCE, TARGET, CONTEXT, 2, 1000, mode);
        assertEquals(ReversibleFactorSearch.Status.NO_PATH_WITHIN_DEPTH, shallow.status());
        assertEquals(2, reached.witness().size());
        assertEquals(0, reached.work().reverse().calls());
        assertTrue(reached.work().forward().calls() > shallow.work().forward().calls());
        assertTrue(reached.work().replay().calls() > 0);
        assertTrue(SEARCH.replays(SOURCE, TARGET, CONTEXT, reached.witness()));
    }

    @Test void twoFrontiersMeetAndReplayTheActualDirectedProductionSteps() {
        var run = SEARCH.search(SOURCE, TARGET, CONTEXT, 2, 1000, ReversibleFactorSearch.Mode.BIDIRECTIONAL);
        assertEquals(ReversibleFactorSearch.Status.REACHED, run.status());
        var path = run.witness();
        assertEquals(2, path.size());
        assertTrue(path.stream().allMatch(s -> s.primitive().rule().equals("ast_factor_common_left")));
        assertTrue(path.stream().allMatch(s -> s.primitive().primitiveRuleIds().equals(List.of(s.primitive().rule()))));
        assertTrue(run.work().forward().calls() > 0 && run.work().reverse().calls() > 0 && run.work().replay().calls() > 0);
        assertEquals(2, SEARCH.search(SOURCE, TARGET, CONTEXT, 2, 1000, ReversibleFactorSearch.Mode.ONE_SIDED).witness().size());
        assertTrue(SEARCH.replays(SOURCE, TARGET, CONTEXT, path));
        assertFalse(SEARCH.replays(SOURCE, TARGET, AssumptionSignature.ofExpressions(List.of()), path));
        assertFalse(SEARCH.replays(SOURCE, TARGET, CONTEXT, path.reversed()));
        var original = path.getFirst().primitive();
        var wrongApplication = new de.regelsuche.transform.Transformation(original.rule(), original.transformedExpression(),
            original.kind(), original.mayIncreaseComplexity(), original.estimatedCostDelta(), true, "forged-occurrence");
        var changed = new ReversibleFactorSearch.Step(path.getFirst().from(), path.getFirst().to(), wrongApplication);
        assertFalse(SEARCH.replays(SOURCE, TARGET, CONTEXT, List.of(changed, path.getLast())));
        assertFalse(SEARCH.replays(SOURCE, TARGET, CONTEXT, List.of(path.getFirst())));
        assertFalse(SEARCH.replays("a*(x+y)", "a*x+a*y", CONTEXT, List.of(path.getFirst())));
    }

    @Test void sharedWorkIncludesReverseAndIndependentVerification() {
        for (var mode : ReversibleFactorSearch.Mode.values()) {
            var full = SEARCH.search(SOURCE, TARGET, CONTEXT, 2, 1000, mode);
            int exact = full.work().totalCalls();
            assertEquals(full, SEARCH.search(SOURCE, TARGET, CONTEXT, 2, exact, mode));
            var shortRun = SEARCH.search(SOURCE, TARGET, CONTEXT, 2, exact - 1, mode);
            assertEquals(ReversibleFactorSearch.Status.WORK_EXHAUSTED, shortRun.status());
            assertEquals(exact - 1, shortRun.work().totalCalls());
            assertTrue(shortRun.witness().isEmpty());
            assertEquals(ReversibleFactorSearch.Status.NO_PATH_WITHIN_DEPTH,
                SEARCH.search(SOURCE, TARGET, CONTEXT, 1, 1000, mode).status());
            assertEquals(ReversibleFactorSearch.Status.REACHED, SEARCH.search("x", "x", CONTEXT, 0, 0, mode).status());
        }
    }

    @Test void oracleRepresentsACallCutAsInconclusiveAndChecksTheFullLanguageBound() {
        var limits = new BoundedReachabilityOracle.Budget(3, 3, 100, 100);
        var cut = SEARCH.closure(SOURCE, CONTEXT, limits, 1);
        assertEquals(BoundedReachabilityOracle.Status.BUDGET_INCONCLUSIVE, cut.oracle().status());
        assertEquals("PRIMITIVE_ENGINE_CALL_LIMIT_REACHED", cut.oracle().detailCode());
        assertEquals(1, cut.work().calls());
        assertFalse(cut.oracle().closureComplete());
        var large = String.join("+", java.util.Collections.nCopies(33, "x"));
        assertThrows(IllegalArgumentException.class, () -> SEARCH.search("x", large, CONTEXT, 1, 5,
            ReversibleFactorSearch.Mode.BIDIRECTIONAL));
    }

    @Test void filtersOneWayFormattingEdgesAndConditionalPrimitives() {
        var nativeEngine = new de.regelsuche.transform.AstRewriteTransformationEngine();
        assertTrue(nativeEngine.transform("x+a*(b+c)").stream().anyMatch(t ->
            t.rule().equals("ast_distribute_left_add") && t.transformedExpression().equals("x + a * b + a * c")));
        assertFalse(nativeEngine.transform("x+a*b+a*c").stream().anyMatch(t ->
            t.rule().equals("ast_factor_common_left") && t.transformedExpression().equals("x + a * (b + c)")));
        for (var mode : ReversibleFactorSearch.Mode.values()) {
            assertEquals(ReversibleFactorSearch.Status.NO_PATH_WITHIN_DEPTH,
                SEARCH.search("(x*y)/x", "y", CONTEXT, 3, 1000, mode).status());
            assertEquals(ReversibleFactorSearch.Status.NO_PATH_WITHIN_DEPTH,
                SEARCH.search("x+a*(b+c)", "x+a*b+a*c", CONTEXT, 1, 1000, mode).status());
        }
    }
}
