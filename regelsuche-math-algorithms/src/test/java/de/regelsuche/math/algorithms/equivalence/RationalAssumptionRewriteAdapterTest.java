package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.EvaluationTask;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.FormationSeed;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.FormationStatus;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.ResourceBudget;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.SearchStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class RationalAssumptionRewriteAdapterTest {
    private static final ResourceBudget BUDGET =
        new ResourceBudget(5, 200, 200);

    private final RationalAssumptionRewriteAdapter adapter =
        new RationalAssumptionRewriteAdapter();

    @Test
    void formsOneGenericCancellationCandidateWithoutTargets() {
        var formation = adapter.formCandidate(formationSeeds(), BUDGET);

        assertEquals(FormationStatus.SELECTED, formation.status());
        var candidate = formation.candidate().orElseThrow();
        assertEquals(
            RationalAssumptionRewriteAdapter.CANDIDATE_FORM_ID,
            candidate.candidateFormId());
        assertEquals(
            RationalAssumptionRewriteAdapter.OPERATOR_ID,
            candidate.operatorId());
        assertEquals(List.of(
            "case-01-seed-1",
            "case-01-seed-2",
            "case-02-seed-1",
            "case-02-seed-2"), candidate.supportSeedIds());
        assertEquals(
            RationalAssumptionRewriteAdapter.FROZEN_PRIMITIVE_RULE_IDS,
            candidate.frozenPrimitiveRuleIds());
        assertEquals(4, formation.evidence().size());
        formation.evidence().forEach(item -> {
            assertTrue(item.selectedAstNodes() < item.inputAstNodes());
            assertFalse(item.candidateAssumptions().isEmpty());
            assertEquals(item.leftCrossNormalForm(),
                item.rightCrossNormalForm());
        });
        assertBalanced(formation.resourceUse());
    }

    @Test
    void reachesTrainCancellationAndAffineFamilies() {
        var formation = adapter.formCandidate(formationSeeds(), BUDGET);

        assertReached(formation, new EvaluationTask(
            "case-01-task-1", "(7*z)/z", "7", List.of("z != 0")));
        assertReached(formation, new EvaluationTask(
            "case-01-task-2", "(a*t)/t", "a", List.of("t != 0")));
        assertReached(formation, new EvaluationTask(
            "case-02-task-1",
            "((z+5)*(z-7))/(z+5)",
            "z-7",
            List.of("z != -5")));
        assertReached(formation, new EvaluationTask(
            "case-02-task-2",
            "((a-b)*(a+c))/(a-b)",
            "a+c",
            List.of("a != b")));
    }

    @Test
    void transfersToParameterizedDifferenceOfSquaresTestFamily() {
        var formation = adapter.formCandidate(formationSeeds(), BUDGET);

        var first = assertReached(formation, new EvaluationTask(
            "case-06-task-1",
            "(x^2-a^2)/(x-a)",
            "x+a",
            List.of("x != a")));
        var second = assertReached(formation, new EvaluationTask(
            "case-06-task-2",
            "(y^2-b^2)/(y+b)",
            "y-b",
            List.of("y != -b")));

        assertTrue(first.steps().stream().anyMatch(step ->
            "ast_square_difference_factor".equals(step.ruleId())));
        assertTrue(first.steps().stream().anyMatch(step ->
            RationalAssumptionRewriteAdapter.OPERATOR_ID.equals(
                step.ruleId())));
        assertTrue(second.steps().stream().allMatch(step ->
            RationalAssumptionRewriteAdapter.FROZEN_PRIMITIVE_RULE_IDS
                .contains(step.ruleId())
                || RationalAssumptionRewriteAdapter.OPERATOR_ID.equals(
                    step.ruleId())));
    }

    @Test
    void retainsHonestNoResultsForUnselectedFormsAndLiteralSquareGap() {
        var formation = adapter.formCandidate(formationSeeds(), BUDGET);

        assertNoResult(formation, new EvaluationTask(
            "case-03-task-1",
            "(x^2-1)/(x-1)",
            "x+1",
            List.of("x != 1")));
        assertNoResult(formation, new EvaluationTask(
            "case-04-task-1",
            "1/(x*(x+1))",
            "1/x-1/(x+1)",
            List.of("x != 0", "x != -1")));
        assertNoResult(formation, new EvaluationTask(
            "case-05-task-1",
            "(1/(x+1))/(1/(x-1))",
            "(x-1)/(x+1)",
            List.of("x != -1", "x != 1")));
    }

    @Test
    void doesNotEvaluateHeldOutTasksWhenFormationFails() {
        var formation = adapter.formCandidate(List.of(
            new FormationSeed(
                "near-miss",
                "x/y + z/w",
                List.of("y != 0", "w != 0"),
                "test/near-miss")), BUDGET);

        assertEquals(FormationStatus.NO_CANDIDATE, formation.status());
        var result = adapter.evaluate(new EvaluationTask(
            "held-out",
            "(x^2-a^2)/(x-a)",
            "x+a",
            List.of("x != a")), formation, BUDGET);
        assertEquals(SearchStatus.CANDIDATE_NOT_FORMED,
            result.status());
        assertTrue(result.steps().isEmpty());
        assertBalanced(result.resourceUse());
    }

    private RationalAssumptionRewriteAdapter.SearchResult assertReached(
        RationalAssumptionRewriteAdapter.FormationResult formation,
        EvaluationTask task
    ) {
        var result = adapter.evaluate(task, formation, BUDGET);
        assertEquals(SearchStatus.REACHED_AND_CONFIRMED,
            result.status(), result.detail());
        assertFalse(result.steps().isEmpty());
        assertEquals(task.source(), result.steps().getFirst().source());
        assertBalanced(result.resourceUse());
        return result;
    }

    private void assertNoResult(
        RationalAssumptionRewriteAdapter.FormationResult formation,
        EvaluationTask task
    ) {
        var result = adapter.evaluate(task, formation, BUDGET);
        assertEquals(SearchStatus.NO_RESULT,
            result.status(), result.detail());
        assertTrue(result.steps().isEmpty());
        assertBalanced(result.resourceUse());
    }

    private static List<FormationSeed> formationSeeds() {
        return List.of(
            new FormationSeed(
                "case-01-seed-1",
                "(2*x)/x",
                List.of("x != 0"),
                "candidate-independent/case-01/seed-1"),
            new FormationSeed(
                "case-01-seed-2",
                "(5*y)/y",
                List.of("y != 0"),
                "candidate-independent/case-01/seed-2"),
            new FormationSeed(
                "case-02-seed-1",
                "((x+3)*(x-2))/(x+3)",
                List.of("x != -3"),
                "candidate-independent/case-02/seed-1"),
            new FormationSeed(
                "case-02-seed-2",
                "((y-4)*(y+1))/(y-4)",
                List.of("y != 4"),
                "candidate-independent/case-02/seed-2"));
    }

    private static void assertBalanced(
        RationalAssumptionRewriteAdapter.ResourceUse use
    ) {
        assertEquals(use.configuredExploredStates(),
            use.executedExploredStates()
                + use.remainingExploredStates());
        assertEquals(use.configuredCandidateEvaluations(),
            use.executedCandidateEvaluations()
                + use.remainingCandidateEvaluations());
    }
}
