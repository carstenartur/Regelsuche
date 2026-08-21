package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoundedReachabilityOracleTest {
    private final BoundedReachabilityOracle oracle =
        new BoundedReachabilityOracle();

    @Test
    void retainsTheShortestPrimitiveWitnessInsteadOfTheShallowestMacro() {
        TransformationEngine engine = new MapEngine(Map.of(
            "a", List.of(
                edge("direct-macro", "b", List.of("m1", "m2", "m3")),
                edge("a-to-c", "c", List.of("a-to-c"))),
            "c", List.of(
                edge("c-to-b", "b", List.of("c-to-b")))));

        BoundedReachabilityOracle.Result result = oracle.analyze(
            "a",
            "b",
            AssumptionSignature.ofExpressions(List.of()),
            engine,
            new BoundedReachabilityOracle.Budget(3, 4, 32, 64));

        assertEquals(
            BoundedReachabilityOracle.Status.REACHABLE,
            result.status());
        BoundedReachabilityOracle.Witness witness =
            result.witness().orElseThrow();
        assertEquals(2, witness.depth());
        assertEquals(2, witness.primitiveSteps());
        assertEquals(List.of("a", "c", "b"), witness.states().stream()
            .map(BoundedReachabilityOracle.RetainedState::expression)
            .toList());
        assertEquals(
            List.of("a-to-c", "c-to-b"),
            witness.primitiveRuleIds());
        assertTrue(witness.additionalAssumptions().isEmpty());
    }

    @Test
    void distinguishesDeclaredFromAdditionalAssumptionReachability() {
        TransformationEngine engine = new AstRewriteTransformationEngine();
        BoundedReachabilityOracle.Budget budget =
            new BoundedReachabilityOracle.Budget(1, 1, 128, 256);

        BoundedReachabilityOracle.Result conditional = oracle.analyze(
            "(x * y) / x",
            "y",
            AssumptionSignature.ofExpressions(List.of()),
            engine,
            budget);

        assertEquals(
            BoundedReachabilityOracle.Status
                .REACHABLE_ONLY_WITH_ADDITIONAL_ASSUMPTIONS,
            conditional.status());
        assertEquals(
            List.of("x != 0"),
            conditional.witness().orElseThrow().additionalAssumptions());
        assertTrue(conditional.closureComplete());

        BoundedReachabilityOracle.Result declared = oracle.analyze(
            "(x * y) / x",
            "y",
            AssumptionSignature.ofExpressions(List.of("0 != x")),
            engine,
            budget);

        assertEquals(
            BoundedReachabilityOracle.Status.REACHABLE,
            declared.status());
        assertTrue(declared.witness().orElseThrow()
            .additionalAssumptions().isEmpty());
    }

    @Test
    void separatesCompleteBoundedClosureFromExecutionBudgetFailure() {
        TransformationEngine engine = new MapEngine(Map.of(
            "a", List.of(edge("a-to-b", "b", List.of("a-to-b")))));

        BoundedReachabilityOracle.Result complete = oracle.analyze(
            "a",
            "z",
            AssumptionSignature.ofExpressions(List.of()),
            engine,
            new BoundedReachabilityOracle.Budget(2, 2, 8, 8));

        assertEquals(
            BoundedReachabilityOracle.Status
                .UNREACHABLE_IN_COMPLETE_BOUNDED_CLOSURE,
            complete.status());
        assertTrue(complete.closureComplete());
        assertFalse(complete.work().visitedStateLimitReached());
        assertFalse(complete.work().generatedTransitionLimitReached());

        BoundedReachabilityOracle.Result capped = oracle.analyze(
            "a",
            "z",
            AssumptionSignature.ofExpressions(List.of()),
            engine,
            new BoundedReachabilityOracle.Budget(2, 2, 1, 8));

        assertEquals(
            BoundedReachabilityOracle.Status.BUDGET_INCONCLUSIVE,
            capped.status());
        assertTrue(capped.work().visitedStateLimitReached());
        assertEquals(1, capped.work().visitedStateLimitTransitions());
        assertFalse(capped.closureComplete());
    }

    @Test
    void primitiveWorkLimitDefinesTheClaimedFiniteClosure() {
        TransformationEngine engine = new MapEngine(Map.of(
            "a", List.of(
                edge("two-step-macro", "b", List.of("p1", "p2")))));

        BoundedReachabilityOracle.Result result = oracle.analyze(
            "a",
            "b",
            AssumptionSignature.ofExpressions(List.of()),
            engine,
            new BoundedReachabilityOracle.Budget(2, 1, 8, 8));

        assertEquals(
            BoundedReachabilityOracle.Status
                .UNREACHABLE_IN_COMPLETE_BOUNDED_CLOSURE,
            result.status());
        assertEquals(1, result.work().outsidePrimitiveWorkTransitions());
        assertEquals(
            BoundedReachabilityOracle.EdgeDisposition
                .OUTSIDE_PRIMITIVE_WORK_BOUND,
            result.edges().getFirst().disposition());
    }

    @Test
    void sameExpressionWithDifferentAssumptionsRemainsDistinct() {
        TransformationEngine engine = new MapEngine(Map.of(
            "a", List.of(
                edge(
                    "requires-x",
                    "b",
                    List.of("requires-x"),
                    List.of("x != 0")),
                edge(
                    "requires-y",
                    "b",
                    List.of("requires-y"),
                    List.of("y != 0")))));

        BoundedReachabilityOracle.Result result = oracle.analyze(
            "a",
            "z",
            AssumptionSignature.ofExpressions(List.of()),
            engine,
            new BoundedReachabilityOracle.Budget(1, 1, 8, 8));

        assertEquals(3, result.states().size());
        assertEquals(2, result.states().stream()
            .filter(state -> state.expression().equals("b"))
            .count());
        assertEquals(
            List.of("x != 0", "y != 0"),
            result.states().stream()
                .filter(state -> state.expression().equals("b"))
                .flatMap(state ->
                    state.assumptions().normalizedAssumptions().stream())
                .sorted()
                .toList());
    }

    @Test
    void transitionCapMayRetainAConditionalWitnessButStaysInconclusive() {
        TransformationEngine engine = new MapEngine(Map.of(
            "a", List.of(
                edge(
                    "conditional-target",
                    "b",
                    List.of("conditional-target"),
                    List.of("x != 0")),
                edge("other", "c", List.of("other-1", "other-2"))),
            "c", List.of(edge("c-to-d", "d", List.of("c-to-d")))));

        BoundedReachabilityOracle.Result result = oracle.analyze(
            "a",
            "b",
            AssumptionSignature.ofExpressions(List.of()),
            engine,
            new BoundedReachabilityOracle.Budget(3, 3, 16, 2));

        assertEquals(
            BoundedReachabilityOracle.Status.BUDGET_INCONCLUSIVE,
            result.status());
        assertTrue(result.witness().isPresent());
        assertTrue(result.work().generatedTransitionLimitReached());
        assertTrue(result.detailCode().endsWith(
            "_AFTER_CONDITIONAL_WITNESS"));
    }

    @Test
    void engineFailureIsRetainedAsTechnicalRatherThanUnreachable() {
        TransformationEngine failing = expression -> {
            throw new IllegalStateException("deliberate failure");
        };

        BoundedReachabilityOracle.Result result = oracle.analyze(
            "a",
            "b",
            AssumptionSignature.ofExpressions(List.of()),
            failing,
            new BoundedReachabilityOracle.Budget(2, 2, 8, 8));

        assertEquals(
            BoundedReachabilityOracle.Status.TECHNICAL_FAILURE,
            result.status());
        assertTrue(result.technicalDetail().contains("deliberate failure"));
        assertFalse(result.closureComplete());
    }

    @Test
    void invalidInputsAndBudgetsFailClosed() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new BoundedReachabilityOracle.Budget(-1, 1, 1, 1));
        assertThrows(
            IllegalArgumentException.class,
            () -> oracle.analyze(
                " ",
                "b",
                AssumptionSignature.ofExpressions(List.of()),
                expression -> List.of(),
                new BoundedReachabilityOracle.Budget(1, 1, 1, 1)));
    }

    private static Transformation edge(
        String rule,
        String target,
        List<String> primitives
    ) {
        return edge(rule, target, primitives, List.of());
    }

    private static Transformation edge(
        String rule,
        String target,
        List<String> primitives,
        List<String> assumptions
    ) {
        return new Transformation(
            rule,
            target,
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            rule + ":" + target + ":" + assumptions,
            assumptions,
            "test",
            "PROJECT",
            primitives);
    }

    private static final class MapEngine implements TransformationEngine {
        private final Map<String, List<Transformation>> transitions;

        private MapEngine(
            Map<String, List<Transformation>> transitions
        ) {
            this.transitions = new LinkedHashMap<>(transitions);
        }

        @Override
        public List<Transformation> transform(String expression) {
            return transitions.getOrDefault(expression, List.of());
        }
    }
}
