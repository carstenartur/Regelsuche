package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoundedRewriteReachabilityOracleTest {

    @Test
    void returnsDeterministicShortestWitness() {
        TransformationEngine engine = graph(Map.of(
            "A", List.of(edge("z_to_c", "C"), edge("a_to_b", "B")),
            "B", List.of(edge("b_to_d", "D")),
            "C", List.of(edge("c_to_d", "D"))));
        BoundedRewriteReachabilityOracle oracle =
            new BoundedRewriteReachabilityOracle(engine);

        BoundedRewriteReachabilityOracle.Result result = oracle.search(
            "A",
            "D",
            new BoundedRewriteReachabilityOracle.Budget(4, 20));

        assertEquals(BoundedRewriteReachabilityOracle.Status.REACHABLE, result.status());
        assertEquals(List.of("a_to_b", "b_to_d"),
            result.witness().stream()
                .map(BoundedRewriteReachabilityOracle.Step::rule)
                .toList());
        assertEquals(List.of("B", "D"),
            result.witness().stream()
                .map(BoundedRewriteReachabilityOracle.Step::expressionAfter)
                .toList());
        assertEquals(4, result.visitedStates());
        assertEquals(4, result.generatedTransitions());
        assertEquals(2, result.maximumDepthReached());
        assertFalse(result.depthLimitReached());
        assertFalse(result.stateLimitReached());
    }

    @Test
    void provesTargetAbsentWhenFiniteClosureIsExhausted() {
        TransformationEngine engine = graph(Map.of(
            "A", List.of(edge("a_to_b", "B")),
            "B", List.of(edge("b_to_a", "A"))));
        BoundedRewriteReachabilityOracle oracle =
            new BoundedRewriteReachabilityOracle(engine);

        BoundedRewriteReachabilityOracle.Result result = oracle.search(
            "A",
            "Z",
            new BoundedRewriteReachabilityOracle.Budget(10, 20));

        assertEquals(
            BoundedRewriteReachabilityOracle.Status
                .UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE,
            result.status());
        assertEquals(2, result.visitedStates());
        assertEquals(2, result.generatedTransitions());
        assertTrue(result.witness().isEmpty());
        assertFalse(result.depthLimitReached());
        assertFalse(result.stateLimitReached());
    }

    @Test
    void reportsDepthBoundAsInconclusiveOnlyWhenAnUnseenSuccessorExists() {
        TransformationEngine engine = graph(Map.of(
            "A", List.of(edge("a_to_b", "B")),
            "B", List.of(edge("b_to_c", "C"))));
        BoundedRewriteReachabilityOracle oracle =
            new BoundedRewriteReachabilityOracle(engine);

        BoundedRewriteReachabilityOracle.Result result = oracle.search(
            "A",
            "Z",
            new BoundedRewriteReachabilityOracle.Budget(1, 20));

        assertEquals(
            BoundedRewriteReachabilityOracle.Status.BUDGET_INCONCLUSIVE,
            result.status());
        assertEquals(2, result.visitedStates());
        assertEquals(2, result.generatedTransitions());
        assertTrue(result.depthLimitReached());
        assertFalse(result.stateLimitReached());
    }

    @Test
    void finiteTerminalStateAtDepthBoundStillCompletesTheClosure() {
        TransformationEngine engine = graph(Map.of(
            "A", List.of(edge("a_to_b", "B"))));
        BoundedRewriteReachabilityOracle oracle =
            new BoundedRewriteReachabilityOracle(engine);

        BoundedRewriteReachabilityOracle.Result result = oracle.search(
            "A",
            "Z",
            new BoundedRewriteReachabilityOracle.Budget(1, 20));

        assertEquals(
            BoundedRewriteReachabilityOracle.Status
                .UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE,
            result.status());
        assertFalse(result.depthLimitReached());
        assertFalse(result.stateLimitReached());
    }

    @Test
    void reportsVisitedStateBoundWithoutClaimingUnreachability() {
        TransformationEngine engine = graph(Map.of(
            "A", List.of(edge("a_to_b", "B"), edge("a_to_c", "C"))));
        BoundedRewriteReachabilityOracle oracle =
            new BoundedRewriteReachabilityOracle(engine);

        BoundedRewriteReachabilityOracle.Result result = oracle.search(
            "A",
            "Z",
            new BoundedRewriteReachabilityOracle.Budget(5, 2));

        assertEquals(
            BoundedRewriteReachabilityOracle.Status.BUDGET_INCONCLUSIVE,
            result.status());
        assertEquals(2, result.visitedStates());
        assertEquals(2, result.generatedTransitions());
        assertFalse(result.depthLimitReached());
        assertTrue(result.stateLimitReached());
    }

    @Test
    void canonicalIdentityCanRecognizeEquivalentRepresentations() {
        TransformationEngine engine = graph(Map.of(
            "A", List.of(edge("a_to_spaced_b", " B "))));
        BoundedRewriteReachabilityOracle oracle =
            new BoundedRewriteReachabilityOracle(engine, value -> value.trim().toLowerCase());

        BoundedRewriteReachabilityOracle.Result result = oracle.search(
            " A ",
            "b",
            new BoundedRewriteReachabilityOracle.Budget(1, 2));

        assertEquals(BoundedRewriteReachabilityOracle.Status.REACHABLE, result.status());
        assertEquals(1, result.witness().size());
    }

    @Test
    void sourceEqualToTargetIsAZeroStepWitness() {
        BoundedRewriteReachabilityOracle oracle =
            new BoundedRewriteReachabilityOracle(expression -> List.of());

        BoundedRewriteReachabilityOracle.Result result = oracle.search(
            "A",
            " A ",
            new BoundedRewriteReachabilityOracle.Budget(0, 1));

        assertEquals(BoundedRewriteReachabilityOracle.Status.REACHABLE, result.status());
        assertTrue(result.witness().isEmpty());
        assertEquals(1, result.visitedStates());
        assertEquals(0, result.generatedTransitions());
    }

    @Test
    void invalidBudgetsAndExpressionsFailClosed() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new BoundedRewriteReachabilityOracle.Budget(-1, 1));
        assertThrows(
            IllegalArgumentException.class,
            () -> new BoundedRewriteReachabilityOracle.Budget(1, 0));

        BoundedRewriteReachabilityOracle oracle =
            new BoundedRewriteReachabilityOracle(expression -> List.of());
        assertThrows(
            IllegalArgumentException.class,
            () -> oracle.search(
                " ",
                "B",
                new BoundedRewriteReachabilityOracle.Budget(1, 2)));
    }

    private static TransformationEngine graph(
        Map<String, List<Transformation>> transitions
    ) {
        return expression -> transitions.getOrDefault(expression, List.of());
    }

    private static Transformation edge(String rule, String target) {
        return new Transformation(rule, target, List.of(), "root");
    }
}
