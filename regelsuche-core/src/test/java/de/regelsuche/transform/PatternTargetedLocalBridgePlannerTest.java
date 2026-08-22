package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.transform.PatternTargetedLocalBridgeEvidence.Budget;
import de.regelsuche.transform.PatternTargetedLocalBridgeEvidence.PlanAttempt;
import de.regelsuche.transform.PatternTargetedLocalBridgeEvidence.PreparedBridge;
import de.regelsuche.transform.PatternTargetedLocalBridgeEvidence.Status;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PatternTargetedLocalBridgePlannerTest {
    private static final AssumptionSignature NO_ASSUMPTIONS =
        AssumptionSignature.ofExpressions(List.of());
    private static final String SYNTHETIC_INVENTORY_HASH =
        "sha256:" + "1".repeat(64);

    @Test
    void directMatchUsesConcretePrincipalReplayWithoutPreparation() {
        PatternTargetedLocalBridgePlanner planner = planner(
            literalRule("principal", "D", "Z"),
            Map.of());

        PlanAttempt result = planner.plan(
            "D",
            NO_ASSUMPTIONS,
            Budget.safeDefaults());

        assertEquals(Status.DIRECT_MATCH_AVAILABLE, result.status());
        assertEquals(
            "Z",
            result.directPrincipalReplay().orElseThrow()
                .transformedExpression());
        assertTrue(result.preparedBridge().isEmpty());
        assertEquals(0, result.work().generatedTransitions());
        assertEquals(1, result.work().visitedStates());
    }

    @Test
    void returnsDeterministicShortestNearMatchBridge() {
        PatternRewriteRule principal = literalRule(
            "principal",
            "D",
            "Z");
        TransformationEngine graph = graph(Map.of(
            "A", List.of(edge("a_to_c", "C"), edge("a_to_b", "B")),
            "B", List.of(edge("b_to_d", "D")),
            "C", List.of(edge("c_to_e", "E"))));
        PatternTargetedLocalBridgePlanner planner =
            new PatternTargetedLocalBridgePlanner(
                principal,
                graph,
                SYNTHETIC_INVENTORY_HASH);

        PlanAttempt first = planner.plan(
            "A",
            NO_ASSUMPTIONS,
            Budget.safeDefaults());
        PlanAttempt second = planner.plan(
            "A",
            NO_ASSUMPTIONS,
            Budget.safeDefaults());

        assertEquals(Status.PREPARED, first.status());
        PreparedBridge bridge = first.preparedBridge().orElseThrow();
        assertEquals(2, bridge.preparationSteps().size());
        assertEquals(
            List.of("a_to_b", "b_to_d"),
            bridge.preparationSteps().stream()
                .map(PatternTargetedLocalBridgeEvidence.BridgeStep::ruleId)
                .toList());
        assertEquals("D", bridge.terminalPreparedExpression());
        assertEquals("Z", bridge.resultExpression());
        assertEquals(first, second);
        assertTrue(planner.verify(bridge));
        assertEquals(
            first.work().generatedTransitions(),
            first.work().admittedTransitions()
                + first.work().duplicateTransitions()
                + first.work().principalRuleTransitions()
                + first.work().unsafeTransitions()
                + first.work().technicalFailureTransitions()
                + first.work().expressionLimitTransitions()
                + first.work().primitiveLimitTransitions()
                + first.work().successorLimitTransitions()
                + first.work().depthLimitTransitions()
                + first.work().visitedLimitTransitions()
                + first.work().terminalSelectionTransitions());
    }

    @Test
    void completeFiniteClosureProducesAConclusiveNoBridgeResult() {
        PatternTargetedLocalBridgePlanner planner = planner(
            literalRule("principal", "Z", "Q"),
            Map.of(
                "A", List.of(edge("a_to_b", "B")),
                "B", List.of(edge("b_to_a", "A"))));

        PlanAttempt result = planner.plan(
            "A",
            NO_ASSUMPTIONS,
            Budget.safeDefaults());

        assertEquals(
            Status.NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
            result.status());
        assertFalse(result.work().inconclusive());
        assertEquals(2, result.work().visitedStates());
        assertEquals(2, result.work().generatedTransitions());
        assertEquals(1, result.work().duplicateTransitions());
    }

    @Test
    void everyMechanicalCeilingFailsClosedAsInconclusive() {
        PatternRewriteRule principal = literalRule(
            "principal",
            "D",
            "Z");
        TransformationEngine twoStep = graph(Map.of(
            "A", List.of(edge("a_to_b", "B")),
            "B", List.of(edge("b_to_d", "D"))));

        assertEquals(
            Status.BUDGET_INCONCLUSIVE,
            new PatternTargetedLocalBridgePlanner(
                principal,
                twoStep,
                SYNTHETIC_INVENTORY_HASH)
                .plan("A", NO_ASSUMPTIONS, budget(1, 20, 20, 20, 20, 20))
                .status());
        assertEquals(
            Status.BUDGET_INCONCLUSIVE,
            new PatternTargetedLocalBridgePlanner(
                principal,
                twoStep,
                SYNTHETIC_INVENTORY_HASH)
                .plan("A", NO_ASSUMPTIONS, budget(4, 1, 20, 20, 20, 20))
                .status());
        assertEquals(
            Status.BUDGET_INCONCLUSIVE,
            new PatternTargetedLocalBridgePlanner(
                principal,
                twoStep,
                SYNTHETIC_INVENTORY_HASH)
                .plan("A", NO_ASSUMPTIONS, budget(4, 20, 0, 20, 20, 20))
                .status());

        TransformationEngine macro = graph(Map.of(
            "A", List.of(edge(
                "macro_to_d",
                "D",
                List.of(),
                List.of("primitive_1", "primitive_2"),
                true))));
        assertEquals(
            Status.BUDGET_INCONCLUSIVE,
            new PatternTargetedLocalBridgePlanner(
                principal,
                macro,
                SYNTHETIC_INVENTORY_HASH)
                .plan("A", NO_ASSUMPTIONS, budget(4, 20, 20, 1, 20, 20))
                .status());

        TransformationEngine large = graph(Map.of(
            "A", List.of(edge("large", "B + C"))));
        assertEquals(
            Status.BUDGET_INCONCLUSIVE,
            new PatternTargetedLocalBridgePlanner(
                principal,
                large,
                SYNTHETIC_INVENTORY_HASH)
                .plan("A", NO_ASSUMPTIONS, budget(4, 20, 20, 20, 1, 20))
                .status());

        TransformationEngine alternatives = graph(Map.of(
            "A", List.of(edge("a_to_b", "B"), edge("a_to_c", "C"))));
        assertEquals(
            Status.BUDGET_INCONCLUSIVE,
            new PatternTargetedLocalBridgePlanner(
                principal,
                alternatives,
                SYNTHETIC_INVENTORY_HASH)
                .plan("A", NO_ASSUMPTIONS, budget(4, 20, 20, 20, 20, 1))
                .status());
    }

    @Test
    void assumptionsParticipateInVisitedStateIdentity() {
        TransformationEngine graph = graph(Map.of(
            "A", List.of(
                edge("plain", "B"),
                edge(
                    "conditional",
                    "B",
                    List.of("x != 0"),
                    List.of("conditional"),
                    true))));
        PatternTargetedLocalBridgePlanner planner =
            new PatternTargetedLocalBridgePlanner(
                literalRule("principal", "D", "Z"),
                graph,
                SYNTHETIC_INVENTORY_HASH);

        PlanAttempt result = planner.plan(
            "A",
            NO_ASSUMPTIONS,
            Budget.safeDefaults());

        assertEquals(
            Status.NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
            result.status());
        assertEquals(3, result.work().visitedStates());
        assertEquals(2, result.work().admittedTransitions());
    }

    @Test
    void principalAndUnsafePreparationEdgesAreExcludedExplicitly() {
        PatternRewriteRule principal = literalRule(
            "principal",
            "D",
            "Z");
        TransformationEngine graph = expression -> "A".equals(expression)
            ? List.of(
                edge("principal", "D"),
                edge(
                    "unsafe",
                    "D",
                    List.of(),
                    List.of("unsafe"),
                    false))
            : List.of();
        PatternTargetedLocalBridgePlanner planner =
            new PatternTargetedLocalBridgePlanner(
                principal,
                graph,
                SYNTHETIC_INVENTORY_HASH);

        PlanAttempt result = planner.plan(
            "A",
            NO_ASSUMPTIONS,
            Budget.safeDefaults());

        assertEquals(
            Status.NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
            result.status());
        assertEquals(1, result.work().principalRuleTransitions());
        assertEquals(1, result.work().unsafeTransitions());
        assertEquals(0, result.work().admittedTransitions());
    }

    @Test
    void algorithmicRuleWithoutSchemaIsUnsupportedRatherThanInferred() {
        RewriteRule algorithmic = AstRewriteTransformationEngine
            .allBuiltInRules()
            .stream()
            .filter(rule -> "ast_cancel_division_factor".equals(rule.id()))
            .findFirst()
            .orElseThrow();
        PatternTargetedLocalBridgePlanner planner =
            new PatternTargetedLocalBridgePlanner(
                algorithmic,
                List.of());

        PlanAttempt result = planner.plan(
            "x",
            NO_ASSUMPTIONS,
            Budget.safeDefaults());

        assertEquals(Status.UNSUPPORTED, result.status());
        assertTrue(result.initialAnalysis().isEmpty());
    }

    @Test
    void engineFailureAndCorruptedCertificateFailClosed() {
        PatternRewriteRule principal = literalRule(
            "principal",
            "D",
            "Z");
        PatternTargetedLocalBridgePlanner broken =
            new PatternTargetedLocalBridgePlanner(
                principal,
                expression -> {
                    throw new IllegalStateException("synthetic failure");
                },
                SYNTHETIC_INVENTORY_HASH);
        PlanAttempt failure = broken.plan(
            "A",
            NO_ASSUMPTIONS,
            Budget.safeDefaults());
        assertEquals(Status.BUDGET_INCONCLUSIVE, failure.status());
        assertEquals(1, failure.work().technicalFailures());

        PatternTargetedLocalBridgePlanner valid = planner(
            principal,
            Map.of(
                "A", List.of(edge("a_to_b", "B")),
                "B", List.of(edge("b_to_d", "D"))));
        PreparedBridge bridge = valid.plan(
            "A",
            NO_ASSUMPTIONS,
            Budget.safeDefaults())
            .preparedBridge()
            .orElseThrow();
        PreparedBridge corrupted = new PreparedBridge(
            bridge.plannerId(),
            bridge.sourceExpression(),
            bridge.terminalPreparedExpression(),
            bridge.resultExpression(),
            bridge.initialAssumptions(),
            bridge.finalAssumptions(),
            bridge.principalRuleId(),
            bridge.principalRuleHash(),
            bridge.preparationInventoryHash(),
            bridge.budget(),
            bridge.initialAnalysis(),
            bridge.terminalAnalysis(),
            bridge.preparationSteps(),
            bridge.principalReplay(),
            bridge.work(),
            "sha256:" + "0".repeat(64));
        assertFalse(valid.verify(corrupted));
    }

    @Test
    void generalNeutralElementPreparationAmplifiesSymPyPythagoreanRule() {
        PatternExpr x = PatternExpr.var("X");
        PatternRewriteRule pythagorean = new PatternRewriteRule(
            "sympy.trig.pythagorean",
            PatternExpr.op(
                BinaryOperator.ADD,
                PatternExpr.op(
                    BinaryOperator.POW,
                    PatternExpr.fn("sin", x),
                    PatternExpr.num(2)),
                PatternExpr.op(
                    BinaryOperator.POW,
                    PatternExpr.fn("cos", x),
                    PatternExpr.num(2))),
            PatternExpr.num(1),
            RecognitionProfile.arithmeticAc());
        RewriteRule multiplyOne = AstRewriteTransformationEngine
            .allBuiltInRules()
            .stream()
            .filter(rule -> "ast_multiply_one_right".equals(rule.id()))
            .findFirst()
            .orElseThrow();
        PatternTargetedLocalBridgePlanner planner =
            new PatternTargetedLocalBridgePlanner(
                pythagorean,
                List.of(multiplyOne));

        PlanAttempt direct = planner.plan(
            "sin(x)^2 + cos(x)^2",
            NO_ASSUMPTIONS,
            Budget.safeDefaults());
        PlanAttempt hiddenOneStep = planner.plan(
            "(sin(x) * 1)^2 + cos(x)^2",
            NO_ASSUMPTIONS,
            Budget.safeDefaults());
        PlanAttempt hiddenTwoSteps = planner.plan(
            "((sin(x) * 1) * 1)^2 + cos(x)^2",
            NO_ASSUMPTIONS,
            Budget.safeDefaults());
        PlanAttempt nearMiss = planner.plan(
            "((sin(x) * 1) * 1)^2 + cos(y)^2",
            NO_ASSUMPTIONS,
            Budget.safeDefaults());

        assertEquals(Status.DIRECT_MATCH_AVAILABLE, direct.status());
        assertEquals(Status.PREPARED, hiddenOneStep.status());
        assertEquals(
            1,
            hiddenOneStep.preparedBridge().orElseThrow()
                .preparationSteps().size());
        assertEquals(Status.PREPARED, hiddenTwoSteps.status());
        PreparedBridge twoStepBridge = hiddenTwoSteps
            .preparedBridge()
            .orElseThrow();
        assertEquals(2, twoStepBridge.preparationSteps().size());
        assertEquals("1", twoStepBridge.resultExpression());
        assertTrue(planner.verify(twoStepBridge));
        assertEquals(
            Status.NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
            nearMiss.status());
    }

    @Test
    void invalidBudgetsAndUnsafeConcreteInventoryAreRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> budget(-1, 1, 1, 1, 1, 1));
        PatternRewriteRule principal = literalRule(
            "principal",
            "D",
            "Z");
        RewriteRule unsafe = new PatternRewriteRule(
            "unsafe",
            PatternExpr.variable("A"),
            PatternExpr.variable("B"),
            RewriteKind.NORMALIZE,
            false,
            0,
            false);
        assertThrows(
            IllegalArgumentException.class,
            () -> new PatternTargetedLocalBridgePlanner(
                principal,
                List.of(unsafe)));
    }

    private static PatternTargetedLocalBridgePlanner planner(
        PatternRewriteRule principal,
        Map<String, List<Transformation>> transitions
    ) {
        return new PatternTargetedLocalBridgePlanner(
            principal,
            graph(transitions),
            SYNTHETIC_INVENTORY_HASH);
    }

    private static TransformationEngine graph(
        Map<String, List<Transformation>> transitions
    ) {
        return expression -> transitions.getOrDefault(expression, List.of());
    }

    private static PatternRewriteRule literalRule(
        String id,
        String source,
        String target
    ) {
        return new PatternRewriteRule(
            id,
            PatternExpr.variable(source),
            PatternExpr.variable(target));
    }

    private static Transformation edge(String rule, String target) {
        return edge(
            rule,
            target,
            List.of(),
            List.of(rule),
            true);
    }

    private static Transformation edge(
        String rule,
        String target,
        List<String> assumptions,
        List<String> primitiveRuleIds,
        boolean equivalencePreserving
    ) {
        return new Transformation(
            rule,
            target,
            RewriteKind.NORMALIZE,
            false,
            0,
            equivalencePreserving,
            rule + ":" + target + ":" + String.join(";", assumptions),
            assumptions,
            "test",
            "PROJECT",
            primitiveRuleIds);
    }

    private static Budget budget(
        int depth,
        int states,
        int transitions,
        int primitives,
        int nodes,
        int successors
    ) {
        return new Budget(
            depth,
            states,
            transitions,
            primitives,
            nodes,
            successors,
            20_000,
            10_000);
    }
}
