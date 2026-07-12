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

        HiddenRulePilotEvaluator.Evaluation evaluation = evaluator.evaluate(
            task,
            runtime,
            new HiddenReference(
                "hidden_neutral_element_macro",
                "neutral-element-simplification",
                "(A + 0) * 1",
                "A",
                List.of("neutral-element-simplification", "neutral element macro")));

        assertRediscovered(evaluation);
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

        HiddenRulePilotEvaluator.Evaluation evaluation = evaluator.evaluate(
            task,
            runtime,
            new HiddenReference(
                "hidden_sophie_germain_macro",
                "quartic-factorization",
                "A^4 + 4*B^4",
                "(A^2 + 2*A*B + 2*B^2) * (A^2 - 2*A*B + 2*B^2)",
                List.of("sophie-germain", "quartic-factorization")));

        assertRediscovered(evaluation);
    }

    @Test
    void detectsHiddenIdentifiersInTheRuntimeInputBeforeAnyPublicClaim() {
        RuntimeTask leaking = neutralElementTask("hidden_neutral_element_macro");
        RuntimeResult runtime = runner.run(leaking);
        HiddenReference hidden = new HiddenReference(
            "hidden_neutral_element_macro",
            "neutral-element-simplification",
            "(A + 0) * 1",
            "A",
            List.of());

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

    private static RuntimeTask neutralElementTask(String opaqueId) {
        List<RewriteRule> primitives = AstRewriteTransformationEngine.defaultRules().stream()
            .filter(rule -> rule.id().equals("ast_add_zero_right")
                || rule.id().equals("ast_multiply_one_right"))
            .toList();
        assertEquals(2, primitives.size());
        SearchHeuristic heuristic = new SearchHeuristic(4, 80, 1, 8, 40, 20);
        return new RuntimeTask(
            opaqueId,
            "(x + 0) * 1",
            SearchTarget.valueEquivalent("x"),
            new AstRewriteTransformationEngine(primitives),
            heuristic,
            List.of(
                new PositiveHoldout("p-001", "(y + 0) * 1", "y"),
                new PositiveHoldout("p-002", "(z + 0) * 1", "z")),
            List.of(
                new NegativeHoldout("n-001", "(y + 1) * 1"),
                new NegativeHoldout("n-002", "(y + 0) * 2")));
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
}
