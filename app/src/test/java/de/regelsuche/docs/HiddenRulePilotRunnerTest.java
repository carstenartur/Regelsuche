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
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HiddenRulePilotRunnerTest {
    private final HiddenRulePilotRunner runner = new HiddenRulePilotRunner();
    private final HiddenRulePilotEvaluator evaluator = new HiddenRulePilotEvaluator();
    private final HiddenRuleHoldoutPartition partition = new HiddenRuleHoldoutPartition();

    @Test
    void rediscoversAndCompilesANeutralElementMacroFromPrimitiveSearchOnly() {
        PilotFixture fixture = neutralElementFixture("case-001");
        RuntimeResult runtime = runner.run(fixture.task());

        assertEquals(RuntimeStatus.CANDIDATE_FROZEN, runtime.status(), runtime.toString());
        assertTrue(runtime.primitiveRuleIds().contains("ast_add_zero_right"));
        assertTrue(runtime.primitiveRuleIds().contains("ast_multiply_one_right"));
        assertTrue(runtime.candidate().dynamicRuleId().startsWith("dynamic_hypothesis_"));
        assertTrue(runtime.holdouts().allPassed(), runtime.holdouts().toString());
        assertTrue(runtime.holdouts().materialAblations() >= 1,
            "the learned direct macro must shorten at least one primitive holdout path");
        assertTrue(partition.audit(fixture.task()).passed(),
            partition.audit(fixture.task()).collisions().toString());

        assertRediscovered(evaluator.evaluate(
            fixture.task(), runtime, fixture.reference()));
    }

    @Test
    void rediscoversSophieGermainAsASecondFamilyFromBridgeAndFactorPrimitives() {
        PilotFixture fixture = sophieGermainFixture();
        RuntimeResult runtime = runner.run(fixture.task());

        assertEquals(RuntimeStatus.CANDIDATE_FROZEN, runtime.status(), runtime.toString());
        assertTrue(runtime.primitiveRuleIds().contains(
            DifferenceOfSquaresPreparationOperator.RULE_ID), runtime.toString());
        assertTrue(runtime.primitiveRuleIds().contains("ast_square_difference_factor"),
            runtime.toString());
        assertTrue(runtime.holdouts().allPassed(), runtime.holdouts().toString());
        assertTrue(runtime.holdouts().materialAblations() >= 1, runtime.holdouts().toString());
        assertTrue(partition.audit(fixture.task()).passed(),
            partition.audit(fixture.task()).collisions().toString());

        assertRediscovered(evaluator.evaluate(
            fixture.task(), runtime, fixture.reference()));
    }

    @Test
    void evaluatesFiveRulesAcrossThreeFamiliesWithDisjointHoldoutClasses() {
        List<PilotFixture> fixtures = allFixtures();
        assertEquals(5, fixtures.size());
        Set<String> families = fixtures.stream()
            .map(fixture -> fixture.reference().family())
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(
            Set.of(
                "neutral-element-simplification",
                "quartic-factorization",
                "power-normalization"),
            families);

        for (PilotFixture fixture : fixtures) {
            HiddenRuleHoldoutPartition.SplitAudit split = partition.audit(fixture.task());
            assertTrue(split.passed(), fixture.task().opaqueCaseId() + ": " + split.collisions());

            RuntimeResult runtime = runner.run(fixture.task());
            assertEquals(RuntimeStatus.CANDIDATE_FROZEN, runtime.status(), runtime.toString());
            assertTrue(runtime.holdouts().allPassed(), runtime.holdouts().toString());
            assertRediscovered(evaluator.evaluate(fixture.task(), runtime, fixture.reference()));
        }
    }

    @Test
    void detectsHiddenIdentifiersInTheRuntimeInputBeforeAnyPublicClaim() {
        PilotFixture fixture = neutralElementFixture("hidden_neutral_element_macro");
        RuntimeResult runtime = runner.run(fixture.task());

        HiddenRulePilotEvaluator.Evaluation evaluation =
            evaluator.evaluate(fixture.task(), runtime, fixture.reference());

        assertFalse(evaluation.leakageViolations().isEmpty());
        assertFalse(evaluation.pilotAccepted());
        assertTrue(evaluation.blockers().contains("runtime leakage detected"));
    }

    @Test
    void detectsHiddenPatternsInsideNestedPrimitiveRuleMetadata() {
        PilotFixture fixture = neutralElementFixture("case-leak-check");
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
        RuntimeTask leakedTask = new RuntimeTask(
            fixture.task().opaqueCaseId(),
            fixture.task().inputExpression(),
            fixture.task().target(),
            new HypothesisTransformationEngine(
                new AstRewriteTransformationEngine(leakedRules), List.of(), 0),
            fixture.task().heuristic(),
            fixture.task().positiveHoldouts(),
            fixture.task().negativeHoldouts());
        RuntimeResult runtime = runner.run(fixture.task());

        HiddenRulePilotEvaluator.Evaluation evaluation =
            evaluator.evaluate(leakedTask, runtime, fixture.reference());

        assertFalse(evaluation.leakageViolations().isEmpty());
        assertTrue(evaluation.leakageViolations().stream()
            .anyMatch(violation -> violation.location().equals("PRIMITIVE_RULE_TEMPLATE")));
        assertFalse(evaluation.pilotAccepted());
    }

    @Test
    void rejectsAnOpaquePrimitiveThatSolvesTheTrainingTaskInOneStep() {
        PilotFixture fixture = neutralElementFixture("case-shortcut-check");
        TransformationEngine hiddenShortcut = expression -> expression.equals("(x + 0) * 1")
            ? List.of(new Transformation(
                "opaque_primitive",
                "x",
                RewriteKind.NORMALIZE,
                false,
                0,
                true,
                "opaque-shortcut"))
            : List.of();
        RuntimeTask leakedTask = new RuntimeTask(
            fixture.task().opaqueCaseId(),
            fixture.task().inputExpression(),
            fixture.task().target(),
            hiddenShortcut,
            fixture.task().heuristic(),
            fixture.task().positiveHoldouts(),
            fixture.task().negativeHoldouts());
        RuntimeResult runtime = runner.run(fixture.task());

        HiddenRulePilotEvaluator.Evaluation evaluation =
            evaluator.evaluate(leakedTask, runtime, fixture.reference());

        assertTrue(evaluation.leakageViolations().stream()
            .anyMatch(violation -> violation.location().equals("TRAIN_DIRECT_PRIMITIVE")));
        assertFalse(evaluation.pilotAccepted());
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

    private static HiddenReference reference(
        String id,
        String family,
        String left,
        String right
    ) {
        return new HiddenReference(id, family, left, right, List.of(), List.of(family));
    }

    static List<PilotFixture> allFixtures() {
        return List.of(
            neutralElementFixture("case-001"),
            sophieGermainFixture(),
            new PilotFixture(
                simpleTask(
                    "case-003", "(x * 1) + 0", "x",
                    List.of("ast_multiply_one_right", "ast_add_zero_right"),
                    List.of(
                        new PositiveHoldout("p-005", "((y + z) * 1) + 0", "y + z"),
                        new PositiveHoldout("p-006", "(sin(t) * 1) + 0", "sin(t)")),
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
                        new PositiveHoldout("p-007", "((y + z) - 0) / 1", "y + z"),
                        new PositiveHoldout("p-008", "(sin(t) - 0) / 1", "sin(t)")),
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
                        new PositiveHoldout(
                            "p-009", "((y + z) * (y + z)) * (y + z)", "(y + z)^3"),
                        new PositiveHoldout(
                            "p-010", "(sin(t) * sin(t)) * sin(t)", "sin(t)^3")),
                    List.of(
                        new NegativeHoldout("n-009", "(y * y) * z"),
                        new NegativeHoldout("n-010", "(y * y) + y"))),
                reference(
                    "hidden_cube_normalization_macro",
                    "power-normalization",
                    "(A * A) * A", "A^3")));
    }

    private static PilotFixture neutralElementFixture(String opaqueId) {
        RuntimeTask task = simpleTask(
            opaqueId,
            "(x + 0) * 1",
            "x",
            List.of("ast_add_zero_right", "ast_multiply_one_right"),
            List.of(
                new PositiveHoldout("p-001", "((y + z) + 0) * 1", "y + z"),
                new PositiveHoldout("p-002", "(sin(t) + 0) * 1", "sin(t)")),
            List.of(
                new NegativeHoldout("n-001", "(y + 1) * 1"),
                new NegativeHoldout("n-002", "(y + 0) * 2")));
        return new PilotFixture(task, reference(
            "hidden_neutral_element_macro",
            "neutral-element-simplification",
            "(A + 0) * 1",
            "A"));
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

    private static PilotFixture sophieGermainFixture() {
        SearchHeuristic heuristic = new SearchHeuristic(4, 240, 1, 12, 240, 240);
        HypothesisTransformationEngine primitives = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(),
            List.of(new DifferenceOfSquaresPreparationOperator()),
            8);
        RuntimeTask task = new RuntimeTask(
            "case-002",
            "x^4 + 4*y^4",
            SearchTarget.valueEquivalent(
                "(x^2 + 2*x*y + 2*y^2) * (x^2 - 2*x*y + 2*y^2)"),
            primitives,
            heuristic,
            List.of(
                new PositiveHoldout(
                    "p-003",
                    "(m + 1)^4 + 4*n^4",
                    "((m + 1)^2 + 2*(m + 1)*n + 2*n^2)"
                        + " * ((m + 1)^2 - 2*(m + 1)*n + 2*n^2)"),
                new PositiveHoldout(
                    "p-004",
                    "sin(t)^4 + 4*z^4",
                    "(sin(t)^2 + 2*sin(t)*z + 2*z^2)"
                        + " * (sin(t)^2 - 2*sin(t)*z + 2*z^2)")),
            List.of(
                new NegativeHoldout("n-003", "x^4 + 3*y^4"),
                new NegativeHoldout("n-004", "x^4 + 4*y^3")));
        return new PilotFixture(task, reference(
            "hidden_sophie_germain_macro",
            "quartic-factorization",
            "A^4 + 4*B^4",
            "(A^2 + 2*A*B + 2*B^2) * (A^2 - 2*A*B + 2*B^2)"));
    }

    record PilotFixture(RuntimeTask task, HiddenReference reference) {
    }
}
