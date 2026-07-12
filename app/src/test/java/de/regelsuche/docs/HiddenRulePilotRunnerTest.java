package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.docs.HiddenRulePilotEvaluator.CandidateRelation;
import de.regelsuche.docs.HiddenRulePilotEvaluator.HiddenReference;
import de.regelsuche.docs.HiddenRulePilotRunner.NegativeHoldout;
import de.regelsuche.docs.HiddenRulePilotRunner.PositiveHoldout;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeResult;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeStatus;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HiddenRulePilotRunnerTest {
    private final HiddenRulePilotRunner runner = new HiddenRulePilotRunner();
    private final HiddenRulePilotEvaluator evaluator = new HiddenRulePilotEvaluator();

    @Test
    void rediscoversAndCompilesANeutralElementMacroFromPrimitiveSearchOnly() {
        RuntimeTask task = neutralElementTask("case-001");
        RuntimeResult runtime = runner.run(task);

        assertEquals(RuntimeStatus.CANDIDATE_FROZEN, runtime.status(), runtime.toString());
        assertTrue(runtime.primitiveRuleIds().contains("ast_add_zero_right"));
        assertTrue(runtime.primitiveRuleIds().contains("ast_multiply_one_right"));
        assertTrue(runtime.candidate().dynamicRuleId().startsWith("dynamic_hypothesis_"));
        assertTrue(runtime.holdouts().allPassed(), runtime.holdouts().toString());
        assertTrue(runtime.holdouts().materialAblations() >= 1,
            "the learned direct macro must shorten at least one primitive holdout path");

        assertRediscovered(evaluator.evaluate(
            task,
            runtime,
            reference(
                "hidden_neutral_element_macro",
                "neutral-element-simplification",
                "(A + 0) * 1",
                "A")));
    }

    @Test
    void rediscoversSophieGermainAsASecondFamilyFromBridgeAndFactorPrimitives() {
        RuntimeTask task = sophieGermainTask();
        RuntimeResult runtime = runner.run(task);

        assertEquals(RuntimeStatus.CANDIDATE_FROZEN, runtime.status(), runtime.toString());
        assertTrue(runtime.primitiveRuleIds().contains(
            DifferenceOfSquaresPreparationOperator.RULE_ID), runtime.toString());
        assertTrue(runtime.primitiveRuleIds().contains("ast_square_difference_factor"),
            runtime.toString());
        assertTrue(runtime.holdouts().allPassed(), runtime.holdouts().toString());
        assertTrue(runtime.holdouts().materialAblations() >= 1, runtime.holdouts().toString());

        assertRediscovered(evaluator.evaluate(
            task,
            runtime,
            reference(
                "hidden_sophie_germain_macro",
                "quartic-factorization",
                "A^4 + 4*B^4",
                "(A^2 + 2*A*B + 2*B^2) * (A^2 - 2*A*B + 2*B^2)")));
    }

    @Test
    void completesFiveRulePilotAcrossThreeFamilies() {
        List<PilotFixture> fixtures = List.of(
            new PilotFixture(
                simpleTask(
                    "case-003", "(x * 1) + 0", "x",
                    List.of("ast_multiply_one_right", "ast_add_zero_right"),
                    List.of(
                        new PositiveHoldout("p-005", "(y * 1) + 0", "y"),
                        new PositiveHoldout("p-006", "(z * 1) + 0", "z")),
                    List.of(
                        new NegativeHoldout("n-005", "(y * 2) + 0"),
                        new NegativeHoldout("n-006", "(y * 1) + 1"))),
                reference(
                    "hidden_multiply_then_add_neutral_macro",
                    "neutral-element-simplification",
                    "(A * 1) + 0", "A")),
            new PilotFixture(
                simpleTask(
                    "case-004", "(x - 0) / 1", "x",
                    List.of("ast_subtract_zero", "ast_divide_one"),
                    List.of(
                        new PositiveHoldout("p-007", "(y - 0) / 1", "y"),
                        new PositiveHoldout("p-008", "(z - 0) / 1", "z")),
                    List.of(
                        new NegativeHoldout("n-007", "(y - 1) / 1"),
                        new NegativeHoldout("n-008", "(y - 0) / 2"))),
                reference(
                    "hidden_subtract_then_divide_neutral_macro",
                    "neutral-element-simplification",
                    "(A - 0) / 1", "A")),
            new PilotFixture(
                simpleTask(
                    "case-005", "(x * x) * x", "x^3",
                    List.of("ast_product_to_power_two", "ast_combine_powers"),
                    List.of(
                        new PositiveHoldout("p-009", "(y * y) * y", "y^3"),
                        new PositiveHoldout("p-010", "(z * z) * z", "z^3")),
                    List.of(
                        new NegativeHoldout("n-009", "(y * y) * z"),
                        new NegativeHoldout("n-010", "(y * y) + y"))),
                reference(
                    "hidden_cube_normalization_macro",
                    "power-normalization",
                    "(A * A) * A", "A^3")));

        Set<String> families = fixtures.stream()
            .map(fixture -> fixture.reference().family())
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("neutral-element-simplification", "power-normalization"), families);

        for (PilotFixture fixture : fixtures) {
            RuntimeResult runtime = runner.run(fixture.task());
            assertEquals(RuntimeStatus.CANDIDATE_FROZEN, runtime.status(), runtime.toString());
            assertTrue(runtime.holdouts().allPassed(), runtime.holdouts().toString());
            assertRediscovered(evaluator.evaluate(fixture.task(), runtime, fixture.reference()));
        }
    }

    @Test
    void detectsHiddenIdentifiersInTheRuntimeInputBeforeAnyPublicClaim() {
        RuntimeTask leaking = neutralElementTask("hidden_neutral_element_macro");
        RuntimeResult runtime = runner.run(leaking);
        HiddenReference hidden = reference(
            "hidden_neutral_element_macro",
            "neutral-element-simplification",
            "(A + 0) * 1",
            "A");

        HiddenRulePilotEvaluator.Evaluation evaluation = evaluator.evaluate(leaking, runtime, hidden);

        assertFalse(evaluation.leakageViolations().isEmpty());
        assertFalse(evaluation.pilotAccepted());
        assertTrue(evaluation.blockers().contains("runtime leakage detected"));
    }

    private static void assertRediscovered(HiddenRulePilotEvaluator.Evaluation evaluation) {
        assertTrue(evaluation.leakageViolations().isEmpty(), evaluation.toString());
        assertTrue(evaluation.candidateRelation() == CandidateRelation.EXACT
            || evaluation.candidateRelation() == CandidateRelation.ALPHA_EQUIVALENT
            || evaluation.candidateRelation() == CandidateRelation.SEMANTICALLY_EQUIVALENT,
            evaluation.toString());
        assertTrue(evaluation.materialAblation(), evaluation.toString());
        assertTrue(evaluation.pilotAccepted(), evaluation.blockers().toString());
    }

    private static HiddenReference reference(
        String id,
        String family,
        String left,
        String right
    ) {
        return new HiddenReference(id, family, left, right, List.of(family));
    }

    private static RuntimeTask neutralElementTask(String opaqueId) {
        return simpleTask(
            opaqueId,
            "(x + 0) * 1",
            "x",
            List.of("ast_add_zero_right", "ast_multiply_one_right"),
            List.of(
                new PositiveHoldout("p-001", "(y + 0) * 1", "y"),
                new PositiveHoldout("p-002", "(z + 0) * 1", "z")),
            List.of(
                new NegativeHoldout("n-001", "(y + 1) * 1"),
                new NegativeHoldout("n-002", "(y + 0) * 2")));
    }

    private static RuntimeTask simpleTask(
        String opaqueId,
        String input,
        String target,
        List<String> primitiveRuleIds,
        List<PositiveHoldout> positives,
        List<NegativeHoldout> negatives
    ) {
        List<RewriteRule> primitives = AstRewriteTransformationEngine.defaultRules().stream()
            .filter(rule -> primitiveRuleIds.contains(rule.id()))
            .toList();
        assertEquals(Set.copyOf(primitiveRuleIds),
            primitives.stream().map(RewriteRule::id).collect(java.util.stream.Collectors.toSet()));
        SearchHeuristic heuristic = new SearchHeuristic(4, 80, 1, 8, 40, 20);
        return new RuntimeTask(
            opaqueId,
            input,
            SearchTarget.valueEquivalent(target),
            new AstRewriteTransformationEngine(primitives),
            heuristic,
            positives,
            negatives);
    }

    private static RuntimeTask sophieGermainTask() {
        SearchHeuristic heuristic = new SearchHeuristic(4, 240, 1, 12, 240, 240);
        HypothesisTransformationEngine primitives = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(),
            List.of(new DifferenceOfSquaresPreparationOperator()),
            8);
        return new RuntimeTask(
            "case-002",
            "x^4 + 4*y^4",
            SearchTarget.valueEquivalent(
                "(x^2 + 2*x*y + 2*y^2) * (x^2 - 2*x*y + 2*y^2)"),
            primitives,
            heuristic,
            List.of(
                new PositiveHoldout(
                    "p-003",
                    "m^4 + 4*n^4",
                    "(m^2 + 2*m*n + 2*n^2) * (m^2 - 2*m*n + 2*n^2)"),
                new PositiveHoldout(
                    "p-004",
                    "(u + 1)^4 + 4*z^4",
                    "((u + 1)^2 + 2*(u + 1)*z + 2*z^2)"
                        + " * ((u + 1)^2 - 2*(u + 1)*z + 2*z^2)")),
            List.of(
                new NegativeHoldout("n-003", "x^4 + 3*y^4"),
                new NegativeHoldout("n-004", "x^4 + 4*y^3")));
    }

    private record PilotFixture(RuntimeTask task, HiddenReference reference) {
    }
}
