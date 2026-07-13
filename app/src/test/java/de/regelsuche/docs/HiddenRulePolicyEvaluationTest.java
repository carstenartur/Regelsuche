package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.learning.InMemorySearchExperienceRepository;
import de.regelsuche.search.learning.SearchTrajectoryCollector;
import de.regelsuche.search.learning.SearchTrajectoryContext;
import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.learning.SearchTrajectoryDataset;
import de.regelsuche.search.learning.SearchTrajectoryRun;
import de.regelsuche.search.policy.EmpiricalSearchPolicy;
import de.regelsuche.search.policy.SearchPolicyModel;
import de.regelsuche.search.policy.SearchPolicyModel.Mode;
import de.regelsuche.search.policy.SearchPolicyTrainer;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy.PolicySearchResult;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy.RankingEvent;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.TransformationEngine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Held-out-family evidence for the first explainable policy slice from #219. */
class HiddenRulePolicyEvaluationTest {
    private static final String SCHEMA = "regelsuche.hidden-rule-policy-evaluation/v1";
    private static final String PRODUCER = "hidden-rule-policy-evaluation/v1";
    private static final int REQUIRED_IMPROVEMENT_PERMILLE = 200;

    private static final Map<String, FamilySpec> FAMILIES = Map.of(
        "case-001", new FamilySpec("family-a", DatasetSplit.TRAIN),
        "case-003", new FamilySpec("family-a", DatasetSplit.TRAIN),
        "case-004", new FamilySpec("family-a", DatasetSplit.TRAIN),
        "case-002", new FamilySpec("family-b", DatasetSplit.VALIDATION),
        "case-005", new FamilySpec("family-c", DatasetSplit.TEST));

    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    @Test
    void emitsHonestHeldOutFamilyEvidenceWithoutHiddenReferenceAccess() {
        EvaluationReport report = evaluate();
        String json = report.toJson();
        Path output = Path.of("build", "reports", "held-out-policy", "report.json");
        Path datasetOutput = Path.of("build", "reports", "held-out-policy", "trajectories.jsonl");
        write(output, json);
        write(datasetOutput, report.datasetJsonLines());

        assertEquals(SCHEMA, report.schema());
        assertTrue(report.leakageFree());
        assertEquals(3, report.familyCount());
        assertEquals(3, report.trainRuns());
        assertEquals(1, report.validationRuns());
        assertEquals(1, report.testRuns());
        assertEquals(2, report.cases().size());
        assertEquals(json, report.toJson());
        assertTrue(Files.isRegularFile(output));
        assertTrue(Files.isRegularFile(datasetOutput));
        assertFalse(json.contains("hidden_"));
        assertFalse(json.contains("A^4"));
        assertFalse(report.correctnessLoss());

        assertTrue(report.cases().stream()
            .flatMap(caseReport -> caseReport.outcomes().stream())
            .filter(outcome -> !outcome.policy().equals("static"))
            .allMatch(PolicyOutcome::completeFallback),
            "held-out rule inventories must fall back instead of pretending to generalize");
        assertTrue(report.bestImprovementPermille() < REQUIRED_IMPROVEMENT_PERMILLE);
        assertEquals("NEGATIVE_UNSEEN_RULE_IDS", report.conclusion());
        assertTrue(report.limitingFeature().contains("per-rule"));
    }

    private EvaluationReport evaluate() {
        List<RuntimeTask> tasks = HiddenRulePilotRuntimeCatalog.tasks().stream()
            .sorted(Comparator.comparing(RuntimeTask::opaqueCaseId))
            .toList();
        List<SearchTrajectoryRun> runs = new ArrayList<>();
        Map<String, RuntimeTask> taskById = new LinkedHashMap<>();

        for (RuntimeTask task : tasks) {
            FamilySpec family = family(task.opaqueCaseId());
            SearchTrajectoryCollector collector = new SearchTrajectoryCollector();
            SearchProblem problem = problem(task).withObserver(collector);
            GoalSearchResult result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
            assertTrue(result.reached(), () -> task.opaqueCaseId() + " did not reach its public endpoint");
            SearchTrajectoryContext context = new SearchTrajectoryContext(
                task.opaqueCaseId(),
                family.id(),
                PRODUCER,
                inventoryIds(task.primitiveEngine()),
                family.split());
            runs.add(collector.finish(problem, result, context));
            taskById.put(task.opaqueCaseId(), task);
        }

        SearchTrajectoryDataset dataset = new SearchTrajectoryDataset(runs);
        assertTrue(dataset.leakageFree(), dataset.leakageViolations().toString());
        SearchPolicyTrainer trainer = new SearchPolicyTrainer();
        Map<Mode, SearchPolicyModel> models = new EnumMap<>(Mode.class);
        for (Mode mode : Mode.values()) {
            models.put(mode, trainer.train(dataset, mode, 1));
        }

        InMemorySearchExperienceRepository experiences = new InMemorySearchExperienceRepository();
        runs.stream()
            .filter(run -> run.context().split() == DatasetSplit.TRAIN)
            .forEach(experiences::store);

        List<CaseReport> caseReports = runs.stream()
            .filter(run -> run.context().split() != DatasetSplit.TRAIN)
            .sorted(Comparator.comparing(run -> run.context().runId()))
            .map(run -> evaluateCase(
                taskById.get(run.context().runId()), run.context(), models, experiences))
            .toList();

        int bestImprovement = caseReports.stream()
            .flatMap(caseReport -> caseReport.outcomes().stream())
            .filter(outcome -> !outcome.policy().equals("static"))
            .mapToInt(PolicyOutcome::exploredImprovementPermille)
            .max()
            .orElse(0);
        boolean correctnessLoss = caseReports.stream()
            .flatMap(caseReport -> caseReport.outcomes().stream())
            .anyMatch(PolicyOutcome::correctnessLoss);
        boolean completeHeldOutFallback = caseReports.stream()
            .flatMap(caseReport -> caseReport.outcomes().stream())
            .filter(outcome -> !outcome.policy().equals("static"))
            .allMatch(PolicyOutcome::completeFallback);
        String conclusion = bestImprovement >= REQUIRED_IMPROVEMENT_PERMILLE && !correctnessLoss
            ? "POSITIVE_MATERIAL_IMPROVEMENT"
            : completeHeldOutFallback
                ? "NEGATIVE_UNSEEN_RULE_IDS"
                : "NEGATIVE_NO_MATERIAL_IMPROVEMENT";
        String limitingFeature = completeHeldOutFallback
            ? "per-rule statistics and family-scoped experience cannot score unseen held-out-family rule ids"
            : "available features did not improve the held-out suite by at least 20 percent";

        SearchTrajectoryDataset.DatasetSummary summary = dataset.summary();
        return new EvaluationReport(
            SCHEMA,
            dataset.leakageFree(),
            summary.families(),
            summary.splitBalances().get(DatasetSplit.TRAIN).runs(),
            summary.splitBalances().get(DatasetSplit.VALIDATION).runs(),
            summary.splitBalances().get(DatasetSplit.TEST).runs(),
            models.get(Mode.LINEAR).datasetHash(),
            SearchPolicyModel.FEATURE_SCHEMA,
            modelVersions(models),
            experiences.summary().total(),
            bestImprovement,
            correctnessLoss,
            conclusion,
            limitingFeature,
            caseReports,
            dataset.toJsonLines());
    }

    private CaseReport evaluateCase(
        RuntimeTask task,
        SearchTrajectoryContext context,
        Map<Mode, SearchPolicyModel> models,
        InMemorySearchExperienceRepository experiences
    ) {
        GoalSearchResult staticResult = new BestFirstSearchStrategy()
            .searchWithDiagnostics(problem(task));
        List<PolicyOutcome> outcomes = new ArrayList<>();
        outcomes.add(PolicyOutcome.staticOutcome(staticResult));
        for (Mode mode : Mode.values()) {
            EmpiricalSearchPolicy policy = mode == Mode.LINEAR_WITH_EXPERIENCE
                ? new EmpiricalSearchPolicy(models.get(mode), experiences, context.family())
                : new EmpiricalSearchPolicy(models.get(mode));
            PolicySearchResult policyResult = new PolicyAwareBestFirstSearchStrategy(policy)
                .searchWithDiagnostics(problem(task));
            outcomes.add(PolicyOutcome.learned(
                mode.name().toLowerCase().replace('_', '-'),
                policyResult,
                staticResult));
        }
        return new CaseReport(
            task.opaqueCaseId(),
            context.family(),
            context.split().name(),
            inventoryIds(task.primitiveEngine()),
            outcomes);
    }

    private SearchProblem problem(RuntimeTask task) {
        return new SearchProblem(
            task.inputExpression(),
            task.primitiveEngine(),
            scorer,
            canonicalizer,
            task.heuristic())
            .withTarget(task.target());
    }

    private static FamilySpec family(String caseId) {
        FamilySpec family = FAMILIES.get(caseId);
        if (family == null) {
            throw new IllegalArgumentException("missing public family assignment for " + caseId);
        }
        return family;
    }

    private static List<String> inventoryIds(TransformationEngine engine) {
        Set<String> ids = new TreeSet<>();
        collectInventory(engine, ids);
        return List.copyOf(ids);
    }

    private static void collectInventory(TransformationEngine engine, Set<String> ids) {
        if (engine instanceof AstRewriteTransformationEngine ast) {
            ast.rules().forEach(rule -> ids.add(rule.id()));
            return;
        }
        if (engine instanceof HypothesisTransformationEngine hypothesis) {
            collectInventory(hypothesis.baseEngine(), ids);
            hypothesis.operators().forEach(operator ->
                ids.add("hypothesis:" + operator.getClass().getSimpleName()));
            return;
        }
        ids.add("engine:" + engine.getClass().getName());
    }

    private static Map<String, String> modelVersions(Map<Mode, SearchPolicyModel> models) {
        Map<String, String> versions = new LinkedHashMap<>();
        models.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> versions.put(
                entry.getKey().name().toLowerCase().replace('_', '-'),
                entry.getValue().modelVersion()));
        return Map.copyOf(versions);
    }

    private static void write(Path output, String content) {
        try {
            Files.createDirectories(output.toAbsolutePath().normalize().getParent());
            Files.writeString(output, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record FamilySpec(String id, DatasetSplit split) {
    }

    private record EvaluationReport(
        String schema,
        boolean leakageFree,
        int familyCount,
        int trainRuns,
        int validationRuns,
        int testRuns,
        String datasetHash,
        String featureSchema,
        Map<String, String> modelVersions,
        int storedExperiences,
        int bestImprovementPermille,
        boolean correctnessLoss,
        String conclusion,
        String limitingFeature,
        List<CaseReport> cases,
        String datasetJsonLines
    ) {
        private EvaluationReport {
            modelVersions = Map.copyOf(modelVersions);
            cases = List.copyOf(cases);
        }

        private String toJson() {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", schema)
                .property("leakageFree", leakageFree)
                .property("familyCount", familyCount)
                .property("trainRuns", trainRuns)
                .property("validationRuns", validationRuns)
                .property("testRuns", testRuns)
                .property("datasetHash", datasetHash)
                .property("featureSchema", featureSchema)
                .property("storedExperiences", storedExperiences)
                .property("requiredImprovementPermille", REQUIRED_IMPROVEMENT_PERMILLE)
                .property("bestImprovementPermille", bestImprovementPermille)
                .property("correctnessLoss", correctnessLoss)
                .property("conclusion", conclusion)
                .property("limitingFeature", limitingFeature)
                .array("models", array -> modelVersions.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> array.objectValue(object -> object
                        .property("mode", entry.getKey())
                        .property("modelVersion", entry.getValue()))))
                .array("cases", array -> cases.forEach(caseReport ->
                    array.objectValue(caseReport::writeJson)))
                .endObject();
            return json.toString();
        }
    }

    private record CaseReport(
        String caseId,
        String family,
        String split,
        List<String> inventoryIds,
        List<PolicyOutcome> outcomes
    ) {
        private CaseReport {
            inventoryIds = List.copyOf(inventoryIds);
            outcomes = List.copyOf(outcomes);
        }

        private void writeJson(JsonWriter json) {
            json.property("caseId", caseId)
                .property("family", family)
                .property("split", split)
                .stringArray("inventoryIds", inventoryIds)
                .array("outcomes", array -> outcomes.forEach(outcome ->
                    array.objectValue(outcome::writeJson)));
        }
    }

    private record PolicyOutcome(
        String policy,
        boolean reached,
        String status,
        int exploredStates,
        int expandedStates,
        int generatedTransformations,
        int enqueuedStates,
        int candidatePrunes,
        int duplicatePrunes,
        int transpositionPrunes,
        int pathLength,
        int policyEvents,
        int fallbackEvents,
        boolean completeFallback,
        List<String> selectedRules,
        int exploredImprovementPermille,
        boolean correctnessLoss
    ) {
        private PolicyOutcome {
            selectedRules = List.copyOf(selectedRules);
        }

        private static PolicyOutcome staticOutcome(GoalSearchResult result) {
            return from("static", result, List.of(), result);
        }

        private static PolicyOutcome learned(
            String policy,
            PolicySearchResult result,
            GoalSearchResult staticResult
        ) {
            return from(policy, result.search(), result.policyEvents(), staticResult);
        }

        private static PolicyOutcome from(
            String policy,
            GoalSearchResult result,
            List<RankingEvent> events,
            GoalSearchResult staticResult
        ) {
            GoalMetrics metrics = result.metrics();
            int improvement = staticResult.metrics().exploredStates() == 0
                ? 0
                : (staticResult.metrics().exploredStates() - metrics.exploredStates())
                    * 1000 / staticResult.metrics().exploredStates();
            List<String> selected = events.stream()
                .filter(RankingEvent::selectedByCandidateBudget)
                .map(RankingEvent::ruleId)
                .toList();
            int fallback = (int) events.stream().filter(RankingEvent::fallback).count();
            return new PolicyOutcome(
                policy,
                result.reached(),
                result.status().name(),
                metrics.exploredStates(),
                metrics.expandedStates(),
                metrics.generatedTransformations(),
                metrics.enqueuedStates(),
                metrics.candidateBudgetPrunes(),
                metrics.duplicatePrunes(),
                metrics.transpositionPrunes(),
                result.reachedState() == null ? -1 : result.reachedState().appliedRuleIds().size(),
                events.size(),
                fallback,
                !events.isEmpty() && fallback == events.size(),
                selected,
                improvement,
                staticResult.reached() && !result.reached());
        }

        private void writeJson(JsonWriter json) {
            json.property("policy", policy)
                .property("reached", reached)
                .property("status", status)
                .property("exploredStates", exploredStates)
                .property("expandedStates", expandedStates)
                .property("generatedTransformations", generatedTransformations)
                .property("enqueuedStates", enqueuedStates)
                .property("candidatePrunes", candidatePrunes)
                .property("duplicatePrunes", duplicatePrunes)
                .property("transpositionPrunes", transpositionPrunes)
                .property("pathLength", pathLength)
                .property("policyEvents", policyEvents)
                .property("fallbackEvents", fallbackEvents)
                .property("completeFallback", completeFallback)
                .stringArray("selectedRules", selectedRules)
                .property("exploredImprovementPermille", exploredImprovementPermille)
                .property("correctnessLoss", correctnessLoss);
        }
    }
}
