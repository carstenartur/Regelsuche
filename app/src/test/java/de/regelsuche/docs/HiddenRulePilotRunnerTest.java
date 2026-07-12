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

        HiddenReference hidden = new HiddenReference(
            "hidden_neutral_element_macro",
            "neutral-element-simplification",
            "(A + 0) * 1",
            "A",
            List.of("neutral-element-simplification", "neutral element macro"));
        HiddenRulePilotEvaluator.Evaluation evaluation = evaluator.evaluate(task, runtime, hidden);

        assertTrue(evaluation.leakageViolations().isEmpty(), evaluation.toString());
        assertTrue(evaluation.candidateRelation() == CandidateRelation.EXACT
            || evaluation.candidateRelation() == CandidateRelation.ALPHA_EQUIVALENT
            || evaluation.candidateRelation() == CandidateRelation.SEMANTICALLY_EQUIVALENT,
            evaluation.toString());
        assertTrue(evaluation.materialAblation());
        assertTrue(evaluation.galleryEvidenceEligible(), evaluation.blockers().toString());
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
        assertFalse(evaluation.galleryEvidenceEligible());
        assertTrue(evaluation.blockers().contains("runtime leakage detected"));
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
}
