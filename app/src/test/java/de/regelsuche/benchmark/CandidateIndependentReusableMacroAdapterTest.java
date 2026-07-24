package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.EvaluationTask;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.FormationResult;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.FormationStatus;
import de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapter.ReplayTrace;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.UtilityOutcome;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CandidateIndependentReusableMacroAdapterTest {
    private final CandidateIndependentReusableMacroAdapter adapter =
        new CandidateIndependentReusableMacroAdapter(profile());

    @Test
    void reproducesAllFrozenTrainReplaysAndFormsThreeMacroSchemas() {
        var formation = adapter.form(trainTraces());

        assertEquals(FormationStatus.SELECTED, formation.status(),
            formation.detail());
        assertEquals(4, formation.replayEvidence().size());
        assertTrue(formation.replayEvidence().stream()
            .allMatch(item -> item.reproduced()
                && !item.actualRuleIds().isEmpty()
                && !item.assignedOperationIds().isEmpty()));
        assertEquals(3, formation.macros().size());
        var polynomialMacro = formation.macros().stream()
            .filter(macro -> macro.supportingTraceIds().equals(List.of(
                "case-13-trace-1", "case-13-trace-2")))
            .findFirst().orElseThrow();
        assertEquals("(A + B) ^ 2", polynomialMacro.rule().leftPattern());
        assertEquals("2 * A * B + A ^ 2 + B * B",
            polynomialMacro.rule().rightPattern());
        assertFalse(polynomialMacro.rule().rightPattern().contains("3 * B - 2"));
        formation.macros().forEach(macro -> {
            assertEquals(
                de.regelsuche.validation.CandidateProofStatus.SYMBOLICALLY_VERIFIED,
                macro.rule().proofStatus());
            assertFalse(macro.atomicSteps().isEmpty());
            assertEquals(1.0, macro.rule().confidenceScore());
        });
    }

    @Test
    void selectsExactlyOneCandidateFromTrainSupportOnly() {
        var formation = adapter.form(trainTraces());
        var selection = CandidateIndependentExactOneMacroSelector.select(formation);

        assertEquals(
            CandidateIndependentExactOneMacroSelector.POLICY,
            selection.policy());
        assertEquals(1, selection.exactOneFormation().macros().size());
        assertEquals(selection.candidate(),
            selection.exactOneFormation().macros().getFirst());
        assertEquals(
            List.of("case-13-trace-1", "case-13-trace-2"),
            selection.candidate().supportingTraceIds());
        assertEquals("(A + B) ^ 2",
            selection.candidate().rule().leftPattern());
    }

    @Test
    void exactOneSelectionDoesNotDependOnFormationListOrder() {
        var formation = adapter.form(trainTraces());
        var expected = CandidateIndependentExactOneMacroSelector
            .select(formation).candidate();
        var reversed = new ArrayList<>(formation.macros());
        Collections.reverse(reversed);
        var reordered = new FormationResult(
            FormationStatus.SELECTED,
            reversed,
            formation.replayEvidence(),
            formation.detail());

        var actual = CandidateIndependentExactOneMacroSelector
            .select(reordered).candidate();

        assertEquals(expected.macroId(), actual.macroId());
        assertEquals(expected.rule().canonicalHash(),
            actual.rule().canonicalHash());
    }

    @Test
    void pairedTrainEvaluationUsesMacroWithoutCorrectnessRegression() {
        var formation = adapter.form(trainTraces());
        var evaluation = adapter.evaluate(new EvaluationTask(
            "case-13-task-1",
            "(z+3)^2",
            "z^2+6*z+9",
            List.of(),
            5,
            800), formation);

        assertTrue(evaluation.baseline().success(),
            evaluation.baseline().detail());
        assertTrue(evaluation.macroEnabled().success(),
            evaluation.macroEnabled().detail());
        assertFalse(evaluation.correctnessRegression());
        assertNotEquals(UtilityOutcome.CORRECTNESS_REGRESSION,
            evaluation.outcome());
        assertEquals(UtilityOutcome.IMPROVED, evaluation.outcome());
        assertTrue(evaluation.macroEnabled().expandedStates()
            < evaluation.baseline().expandedStates());
        assertEquals(1, evaluation.macroEnabled().ruleIds().size());
        assertTrue(evaluation.macroEnabled().ruleIds().getFirst()
            .contains("macro_candidate_independent"));
        assertTrue(evaluation.baseline().detail()
            .contains("production best-first"));
    }

    @Test
    void allFrozenEvaluationFamiliesRemainPairedAndFailClosed() {
        var formation = adapter.form(trainTraces());
        List<EvaluationTask> tasks = frozenTasks();

        var results = tasks.stream()
            .map(task -> adapter.evaluate(task, formation))
            .toList();

        assertEquals(12, results.size());
        assertTrue(results.stream().noneMatch(result ->
            result.outcome() == UtilityOutcome.CORRECTNESS_REGRESSION));
        results.forEach(result -> {
            assertTrue(result.baseline().expandedStates()
                <= task(result.taskId(), tasks).maxExpandedStates());
            assertTrue(result.macroEnabled().expandedStates()
                <= task(result.taskId(), tasks).maxExpandedStates());
            assertFalse(result.detail().isBlank());
        });
        assertEquals(2, count(results, UtilityOutcome.IMPROVED));
        assertEquals(0, count(results, UtilityOutcome.REACHABILITY_GAIN));
        assertEquals(6, count(results, UtilityOutcome.NO_IMPROVEMENT));
        assertEquals(4, count(results, UtilityOutcome.NO_RESULT));
        assertEquals(0, count(results, UtilityOutcome.CORRECTNESS_REGRESSION));
    }

    @Test
    void exactOneCandidateRetainsTheFrozenPairedUtilityFrontier() {
        var selection = CandidateIndependentExactOneMacroSelector.select(
            adapter.form(trainTraces()));
        List<EvaluationTask> tasks = frozenTasks();

        var results = tasks.stream()
            .map(task -> adapter.evaluate(
                task, selection.exactOneFormation()))
            .toList();

        assertEquals(12, results.size());
        assertEquals(2, count(results, UtilityOutcome.IMPROVED));
        assertEquals(0, count(results, UtilityOutcome.REACHABILITY_GAIN));
        assertEquals(6, count(results, UtilityOutcome.NO_IMPROVEMENT));
        assertEquals(4, count(results, UtilityOutcome.NO_RESULT));
        assertEquals(0, count(results, UtilityOutcome.CORRECTNESS_REGRESSION));
        assertTrue(results.stream()
            .noneMatch(result -> result.correctnessRegression()));
        assertTrue(results.stream()
            .flatMap(result -> result.macroEnabled().ruleIds().stream())
            .filter(ruleId -> ruleId.startsWith(
                "macro_candidate_independent_"))
            .allMatch(selection.candidate().macroId()::equals));
        assertTrue(results.stream()
            .flatMap(result -> result.macroEnabled().ruleIds().stream())
            .anyMatch(selection.candidate().macroId()::equals));
    }

    private static long count(
        List<CandidateIndependentReusableMacroAdapter.PairedEvaluation> results,
        UtilityOutcome outcome
    ) {
        return results.stream().filter(result -> result.outcome() == outcome)
            .count();
    }

    private static EvaluationTask task(
        String id,
        List<EvaluationTask> tasks
    ) {
        return tasks.stream().filter(task -> task.taskId().equals(id))
            .findFirst().orElseThrow();
    }

    private static Map<String, List<String>> profile() {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        result.put("ast_add_zero", List.of(
            "ast_add_zero_left", "ast_add_zero_right"));
        result.put("ast_collect_like_terms", List.of(
            "ast_double_term", "ast_canonical_normalize"));
        result.put("ast_distribute", List.of(
            "ast_distribute_left_add", "ast_distribute_right_add",
            "ast_distribute_left_subtract", "ast_distribute_right_subtract"));
        result.put("ast_multiply_identity", List.of(
            "ast_multiply_one_left", "ast_multiply_one_right"));
        result.put("ast_power_expand", List.of(
            "ast_power_two_to_product"));
        result.put("ast_subtract_cancel", List.of(
            "ast_linear_offset_simplify", "ast_canonical_normalize"));
        return result;
    }

    private static List<ReplayTrace> trainTraces() {
        return List.of(
            new ReplayTrace(
                "case-13-trace-1", "(x+1)^2", "x^2+2*x+1",
                List.of("ast_power_expand", "ast_distribute",
                    "ast_collect_like_terms"),
                List.of()),
            new ReplayTrace(
                "case-13-trace-2", "(y+2)^2", "y^2+4*y+4",
                List.of("ast_power_expand", "ast_distribute",
                    "ast_collect_like_terms"),
                List.of()),
            new ReplayTrace(
                "case-14-trace-1", "(x+x)-x", "x",
                List.of("ast_collect_like_terms", "ast_subtract_cancel"),
                List.of()),
            new ReplayTrace(
                "case-14-trace-2", "(y*1)+0", "y",
                List.of("ast_multiply_identity", "ast_add_zero"),
                List.of()));
    }

    private static List<EvaluationTask> frozenTasks() {
        return List.of(
            task("case-13-task-1", "(z+3)^2", "z^2+6*z+9", List.of(), 5, 800),
            task("case-13-task-2", "(a-4)^2", "a^2-8*a+16", List.of(), 5, 800),
            task("case-14-task-1", "(z+z)-z", "z", List.of(), 4, 500),
            task("case-14-task-2", "((a*1)+0)+0", "a", List.of(), 4, 500),
            task("case-15-task-1", "3*(x+2)+2*(x+2)", "5*x+10", List.of(), 6, 1200),
            task("case-15-task-2", "4*(y-1)+3*(y-1)", "7*y-7", List.of(), 6, 1200),
            task("case-16-task-1", "(x+y)^2", "4*x^2", List.of("y = x"), 6, 1200),
            task("case-16-task-2", "(a-b)^2", "0", List.of("b = a"), 6, 1200),
            task("case-17-task-1", "((x^2-1)/(x-1))+0", "x+1", List.of("x != 1"), 7, 1600),
            task("case-17-task-2", "((y^2-4)/(y-2))*1", "y+2", List.of("y != 2"), 7, 1600),
            task("case-18-task-1", "1/(n*(n+1))", "1/n-1/(n+1)", List.of("n != 0", "n != -1"), 7, 1600),
            task("case-18-task-2", "1/(m*(m+2))", "1/(2*m)-1/(2*(m+2))", List.of("m != 0", "m != -2"), 7, 1600));
    }

    private static EvaluationTask task(
        String id,
        String source,
        String target,
        List<String> assumptions,
        int depth,
        int states
    ) {
        return new EvaluationTask(id, source, target, assumptions, depth, states);
    }
}
