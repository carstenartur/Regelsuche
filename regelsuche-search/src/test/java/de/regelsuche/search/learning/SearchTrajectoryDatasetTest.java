package de.regelsuche.search.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.learning.SearchExperienceRepository.SearchExperience;
import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchTrajectoryDatasetTest {
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final SearchHeuristic heuristic = new SearchHeuristic(4, 80, 1, 8, 40, 20);

    @Test
    void labelsTheChosenTransitionAndRetainsFailedAlternatives() {
        SearchTrajectoryRun run = neutralRun("neutral-run");
        List<SearchTrajectoryRecord> decisions = run.records().stream()
            .filter(SearchTrajectoryRecord::decision)
            .toList();

        assertTrue(decisions.stream().anyMatch(record ->
            record.ruleId().equals("remove-one") && record.selectedPath()));
        assertTrue(decisions.stream().anyMatch(record ->
            record.ruleId().equals("remove-zero") && record.selectedPath()));
        assertTrue(decisions.stream().anyMatch(record ->
            record.ruleId().equals("distractor") && !record.selectedPath()));

        InMemorySearchExperienceRepository repository = new InMemorySearchExperienceRepository();
        repository.store(run);
        SearchTrajectoryRecord firstDecision = decisions.getFirst();
        List<SearchExperience> experiences = repository.findByShape(
            run.context().family(), firstDecision.parent().alphaShapeHash(), 10);

        assertTrue(experiences.stream().anyMatch(SearchExperience::successfulChoice));
        assertTrue(experiences.stream().anyMatch(experience -> !experience.successfulChoice()));
        assertEquals(run.decisionCount(), repository.summary().total());
        assertEquals(run.selectedDecisionCount(), repository.summary().successfulChoices());
    }

    @Test
    void exportsByteIdenticalJsonlAndBalancedThreeFamilySummary() {
        List<SearchTrajectoryRun> firstRuns = List.of(
            neutralRun("neutral-run"),
            powerRun("power-run"),
            factorRun("factor-run"));
        List<SearchTrajectoryRun> secondRuns = List.of(
            neutralRun("neutral-run"),
            powerRun("power-run"),
            factorRun("factor-run"));

        TrajectorySplitPlanner.SplitPlan firstPlan =
            new TrajectorySplitPlanner().assignByFamily(firstRuns);
        TrajectorySplitPlanner.SplitPlan secondPlan =
            new TrajectorySplitPlanner().assignByFamily(secondRuns);
        SearchTrajectoryDataset first = SearchTrajectoryDataset.fromPlan(firstPlan);
        SearchTrajectoryDataset second = SearchTrajectoryDataset.fromPlan(secondPlan);

        assertTrue(firstPlan.passed(), firstPlan.leakageViolations().toString());
        assertEquals(first.toJsonLines(), second.toJsonLines());
        assertEquals(first.summaryJson(), second.summaryJson());
        assertEquals(first.toTabularSummary(), second.toTabularSummary());
        assertEquals(3, first.summary().runs());
        assertEquals(3, first.summary().families());
        assertEquals(0, first.summary().leakageViolations());
        assertEquals(0, first.summary().missingDecisionParents());
        assertEquals(0, first.summary().missingDecisionRules());
        assertTrue(first.summary().selectedDecisions() >= 4);
        assertEquals(1, first.summary().splitBalances().get(DatasetSplit.TRAIN).runs());
        assertEquals(1, first.summary().splitBalances().get(DatasetSplit.VALIDATION).runs());
        assertEquals(1, first.summary().splitBalances().get(DatasetSplit.TEST).runs());
        assertEquals(first.summary().records(), first.toJsonLines().lines().count());
        assertEquals(4, first.toTabularSummary().lines().count());
    }

    @Test
    void rejectsAlphaEquivalentTasksAcrossFamilySplits() {
        SearchTrajectoryRun first = neutralRun("first");
        SearchTrajectoryRun second = run(
            "second",
            "other-family",
            "(q + 0) * 1",
            "q",
            Map.of(
                "(q + 0) * 1", List.of(step("remove-one", "q + 0")),
                "q + 0", List.of(step("remove-zero", "q"))));

        TrajectorySplitPlanner.SplitPlan plan =
            new TrajectorySplitPlanner().assignByFamily(List.of(first, second));

        assertFalse(plan.passed());
        assertTrue(plan.leakageViolations().stream()
            .anyMatch(violation -> violation.kind().equals("ALPHA_TASK")));
    }

    @Test
    void attachingACollectorDoesNotChangeSearchSemantics() {
        Map<String, List<Transformation>> graph = neutralGraph();
        TransformationEngine engine = expression -> graph.getOrDefault(expression, List.of());
        SearchProblem plain = problem("(x + 0) * 1", "x", engine, null);
        SearchTrajectoryCollector collector = new SearchTrajectoryCollector();
        SearchProblem observed = problem("(x + 0) * 1", "x", engine, collector);

        var plainResult = new BestFirstSearchStrategy().searchWithDiagnostics(plain);
        var observedResult = new BestFirstSearchStrategy().searchWithDiagnostics(observed);

        assertEquals(plainResult.states(), observedResult.states());
        assertEquals(plainResult.status(), observedResult.status());
        assertEquals(plainResult.reachedState(), observedResult.reachedState());
        collector.finish(observed, observedResult, context("observed", "neutral"));
    }

    private SearchTrajectoryRun neutralRun(String runId) {
        return run(runId, "neutral", "(x + 0) * 1", "x", neutralGraph());
    }

    private Map<String, List<Transformation>> neutralGraph() {
        return Map.of(
            "(x + 0) * 1", List.of(
                step("remove-one", "x + 0"),
                step("distractor", "x + 1")),
            "x + 0", List.of(step("remove-zero", "x")));
    }

    private SearchTrajectoryRun powerRun(String runId) {
        return run(
            runId,
            "power",
            "x * x",
            "x ^ 2",
            Map.of("x * x", List.of(
                step("square", "x ^ 2"),
                step("double", "2 * x"))));
    }

    private SearchTrajectoryRun factorRun(String runId) {
        return run(
            runId,
            "factor",
            "x * y + x * z",
            "x * (y + z)",
            Map.of("x * y + x * z", List.of(
                step("factor", "x * (y + z)"),
                step("noise", "x * y + z"))));
    }

    private SearchTrajectoryRun run(
        String runId,
        String family,
        String root,
        String target,
        Map<String, List<Transformation>> graph
    ) {
        SearchTrajectoryCollector collector = new SearchTrajectoryCollector();
        TransformationEngine engine = expression -> graph.getOrDefault(expression, List.of());
        SearchProblem problem = problem(root, target, engine, collector);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertTrue(result.reached(), result.toString());
        return collector.finish(problem, result, context(runId, family));
    }

    private SearchProblem problem(
        String root,
        String target,
        TransformationEngine engine,
        SearchTrajectoryCollector collector
    ) {
        SearchProblem problem = new SearchProblem(
            root, engine, scorer, canonicalizer, heuristic)
            .withTarget(SearchTarget.syntaxExact(target));
        return collector == null ? problem : problem.withObserver(collector);
    }

    private SearchTrajectoryContext context(String runId, String family) {
        return new SearchTrajectoryContext(
            runId,
            family,
            "test-producer-v1",
            List.of("distractor", "double", "factor", "noise", "remove-one", "remove-zero", "square"),
            DatasetSplit.UNASSIGNED);
    }

    private static Transformation step(String rule, String output) {
        return new Transformation(
            rule,
            output,
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            rule + ":" + output);
    }
}
