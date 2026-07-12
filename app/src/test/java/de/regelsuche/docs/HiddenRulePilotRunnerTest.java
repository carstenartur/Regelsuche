package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.docs.HiddenRulePilotCampaign.PilotCase;
import de.regelsuche.docs.HiddenRulePilotEvaluator.CandidateRelation;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeResult;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeStatus;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class HiddenRulePilotRunnerTest {
    private final HiddenRulePilotRunner runner = new HiddenRulePilotRunner();
    private final HiddenRulePilotEvaluator evaluator = new HiddenRulePilotEvaluator();
    private final HiddenRuleHoldoutPartition partition = new HiddenRuleHoldoutPartition();

    @Test
    void rediscoversAndCompilesANeutralElementMacroFromPrimitiveSearchOnly() {
        PilotCase pilotCase = caseById("case-001");
        RuntimeResult runtime = runner.run(pilotCase.task());

        assertEquals(RuntimeStatus.CANDIDATE_FROZEN, runtime.status(), runtime.toString());
        assertTrue(runtime.primitiveRuleIds().contains("ast_add_zero_right"));
        assertTrue(runtime.primitiveRuleIds().contains("ast_multiply_one_right"));
        assertTrue(runtime.candidate().dynamicRuleId().startsWith("dynamic_hypothesis_"));
        assertTrue(runtime.holdouts().allPassed(), runtime.holdouts().toString());
        assertTrue(runtime.holdouts().materialAblations() >= 1,
            "the learned direct macro must shorten at least one primitive holdout path");
        assertTrue(partition.audit(pilotCase.task()).passed(),
            partition.audit(pilotCase.task()).collisions().toString());

        assertRediscovered(evaluator.evaluate(
            pilotCase.task(), runtime, pilotCase.reference()));
    }

    @Test
    void rediscoversSophieGermainAsASecondFamilyFromBridgeAndFactorPrimitives() {
        PilotCase pilotCase = caseById("case-002");
        RuntimeResult runtime = runner.run(pilotCase.task());

        assertEquals(RuntimeStatus.CANDIDATE_FROZEN, runtime.status(), runtime.toString());
        assertTrue(runtime.primitiveRuleIds().contains(
            DifferenceOfSquaresPreparationOperator.RULE_ID), runtime.toString());
        assertTrue(runtime.primitiveRuleIds().contains("ast_square_difference_factor"),
            runtime.toString());
        assertTrue(runtime.holdouts().allPassed(), runtime.holdouts().toString());
        assertTrue(runtime.holdouts().materialAblations() >= 1, runtime.holdouts().toString());
        assertTrue(partition.audit(pilotCase.task()).passed(),
            partition.audit(pilotCase.task()).collisions().toString());

        assertRediscovered(evaluator.evaluate(
            pilotCase.task(), runtime, pilotCase.reference()));
    }

    @Test
    void detectsHiddenIdentifiersInTheRuntimeInputBeforeAnyPublicClaim() {
        PilotCase pilotCase = caseById("case-001");
        RuntimeTask leakedTask = copyTask(
            pilotCase.task(), "hidden_neutral_element_macro", pilotCase.task().primitiveEngine());
        RuntimeResult runtime = runner.run(pilotCase.task());

        HiddenRulePilotEvaluator.Evaluation evaluation =
            evaluator.evaluate(leakedTask, runtime, pilotCase.reference());

        assertFalse(evaluation.leakageViolations().isEmpty());
        assertFalse(evaluation.pilotAccepted());
        assertTrue(evaluation.blockers().contains("runtime leakage detected"));
    }

    @Test
    void detectsHiddenPatternsInsideNestedPrimitiveRuleMetadata() {
        PilotCase pilotCase = caseById("case-001");
        List<RewriteRule> leakedRules = List.of(new de.regelsuche.transform.PatternRewriteRule(
            "opaque_primitive_rule",
            de.regelsuche.transform.PatternExpr.op(
                de.regelsuche.ast.BinaryOperator.MUL,
                de.regelsuche.transform.PatternExpr.op(
                    de.regelsuche.ast.BinaryOperator.ADD,
                    de.regelsuche.transform.PatternExpr.var("A"),
                    de.regelsuche.transform.PatternExpr.num(0)),
                de.regelsuche.transform.PatternExpr.num(1)),
            de.regelsuche.transform.PatternExpr.var("A")));
        TransformationEngine nested = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(leakedRules), List.of(), 0);
        RuntimeTask leakedTask = copyTask(pilotCase.task(), pilotCase.task().opaqueCaseId(), nested);
        RuntimeResult runtime = runner.run(pilotCase.task());

        HiddenRulePilotEvaluator.Evaluation evaluation =
            evaluator.evaluate(leakedTask, runtime, pilotCase.reference());

        assertTrue(evaluation.leakageViolations().stream()
            .anyMatch(violation -> violation.location().equals("PRIMITIVE_RULE_TEMPLATE")));
        assertFalse(evaluation.pilotAccepted());
    }

    @Test
    void rejectsAnOpaquePrimitiveThatSolvesTheTrainingTaskInOneStep() {
        PilotCase pilotCase = caseById("case-001");
        TransformationEngine hiddenShortcut = expression -> expression.equals("(x + 0) * 1")
            ? List.of(new Transformation(
                "opaque_primitive", "x", RewriteKind.NORMALIZE,
                false, 0, true, "opaque-shortcut"))
            : List.of();
        RuntimeTask leakedTask = copyTask(
            pilotCase.task(), pilotCase.task().opaqueCaseId(), hiddenShortcut);
        RuntimeResult runtime = runner.run(pilotCase.task());

        HiddenRulePilotEvaluator.Evaluation evaluation =
            evaluator.evaluate(leakedTask, runtime, pilotCase.reference());

        assertTrue(evaluation.leakageViolations().stream()
            .anyMatch(violation -> violation.location().equals("TRAIN_DIRECT_PRIMITIVE")));
        assertFalse(evaluation.pilotAccepted());
    }

    private static PilotCase caseById(String id) {
        return HiddenRulePilotCatalog.cases().stream()
            .filter(pilotCase -> pilotCase.task().opaqueCaseId().equals(id))
            .findFirst()
            .orElseThrow();
    }

    private static RuntimeTask copyTask(
        RuntimeTask source,
        String opaqueCaseId,
        TransformationEngine engine
    ) {
        return new RuntimeTask(
            opaqueCaseId,
            source.inputExpression(),
            source.target(),
            engine,
            source.heuristic(),
            source.positiveHoldouts(),
            source.negativeHoldouts());
    }

    private static void assertRediscovered(HiddenRulePilotEvaluator.Evaluation evaluation) {
        assertTrue(evaluation.leakageViolations().isEmpty(), evaluation.toString());
        assertTrue(evaluation.candidateRelation() == CandidateRelation.EXACT
            || evaluation.candidateRelation() == CandidateRelation.ALPHA_EQUIVALENT
            || evaluation.candidateRelation() == CandidateRelation.SEMANTICALLY_EQUIVALENT
            || evaluation.candidateRelation() == CandidateRelation.STRONGER
            || evaluation.candidateRelation() == CandidateRelation.WEAKER,
            evaluation.toString());
        assertTrue(evaluation.materialAblation(), evaluation.toString());
        assertTrue(evaluation.pilotAccepted(), evaluation.blockers().toString());
    }
}
