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
import de.regelsuche.search.policy.DescriptorPolicyModel;
import de.regelsuche.search.policy.DescriptorPolicyModel.Mode;
import de.regelsuche.search.policy.DescriptorPolicyTrainer;
import de.regelsuche.search.policy.DescriptorSearchPolicy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
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

/** Held-out-family evidence for transferable transformation descriptors from #281. */
class HiddenRuleDescriptorPolicyEvaluationTest {
    private static final String SCHEMA =
        "regelsuche.hidden-rule-descriptor-policy-evaluation/v1";
    private static final String PRODUCER = "hidden-rule-descriptor-policy-evaluation/v1";
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
    void scoresHeldOutRuleIdsFromDescriptorEvidenceAndEmitsDeterministicReport() {
        Study study = collectStudy();
        DescriptorPolicyTrainer trainer = new DescriptorPolicyTrainer();
        Map<Mode, DescriptorPolicyModel> models = new EnumMap<>(Mode.class);
        for (Mode mode : Mode.values()) {
            models.put(mode, trainer.train(study.dataset(), mode, 1));
        }
        InMemorySearchExperienceRepository experiences = new InMemorySearchExperienceRepository();
        study.runs().stream()
            .filter(run -> run.context().split() == DatasetSplit.TRAIN)
            .forEach(experiences::store);

        List<CaseEvidence> cases = study.runs().stream()
            .filter(run -> run.context().split() != DatasetSplit.TRAIN)
            .sorted(Comparator.comparing(run -> run.context().runId()))
            .map(run -> evaluateCase(
                study.tasks().get(run.context().runId()), run.context(), models, experiences))
            .toList();
        int nonFallback = cases.stream()
            .flatMap(caseEvidence -> caseEvidence.outcomes().stream())
            .filter(outcome -> outcome.policy().startsWith("descriptor-linear"))
            .mapToInt(Outcome::nonFallbackEvents)
            .sum();
        boolean correctnessLoss = cases.stream()
            .flatMap(caseEvidence -> caseEvidence.outcomes().stream())
            .anyMatch(Outcome::correctnessLoss);
        int bestImprovement = cases.stream()
            .flatMap(caseEvidence -> caseEvidence.outcomes().stream())
            .filter(outcome -> !outcome.policy().equals("static"))
            .mapToInt(Outcome::improvementPermille)
            .max()
            .orElse(0);
        String conclusion = bestImprovement >= REQUIRED_IMPROVEMENT_PERMILLE && !correctnessLoss
            ? "POSITIVE_MATERIAL_IMPROVEMENT"
            : "NEGATIVE_DESCRIPTOR_NO_MATERIAL_IMPROVEMENT";
        String limitingFeature = conclusion.startsWith("POSITIVE")
            ? "none"
            : "available structural deltas transfer to held-out rules but do not yet improve the primary metric by 20 percent";
        String report = report(
            study.dataset(), models, experiences.size(), cases, nonFallback,
            bestImprovement, correctnessLoss, conclusion, limitingFeature);
        Path directory = Path.of("build", "reports", "held-out-descriptor-policy");
        write(directory.resolve("report.json"), report);
        write(directory.resolve("trajectories.jsonl"), study.dataset().toJsonLines());

        assertTrue(study.dataset().leakageFree(), study.dataset().leakageViolations().toString());
        assertTrue(nonFallback > 0,
            "at least one held-out rule must receive a score from descriptor evidence");
        assertFalse(correctnessLoss);
        assertEquals(report, report(
            study.dataset(), models, experiences.size(), cases, nonFallback,
            bestImprovement, correctnessLoss, conclusion, limitingFeature));
        assertFalse(report.contains("hidden_"));
        assertTrue(Files.isRegularFile(directory.resolve("report.json")));
        assertTrue(Files.isRegularFile(directory.resolve("trajectories.jsonl")));
    }

    private Study collectStudy() {
        List<SearchTrajectoryRun> runs = new ArrayList<>();
        Map<String, RuntimeTask> tasks = new LinkedHashMap<>();
        for (RuntimeTask task : HiddenRulePilotRuntimeCatalog.tasks().stream()
                .sorted(Comparator.comparing(RuntimeTask::opaqueCaseId)).toList()) {
            FamilySpec family = FAMILIES.get(task.opaqueCaseId());
            SearchTrajectoryCollector collector = new SearchTrajectoryCollector();
            SearchProblem problem = problem(task).withObserver(collector);
            GoalSearchResult result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
            assertTrue(result.reached(), task.opaqueCaseId());
            SearchTrajectoryContext context = new SearchTrajectoryContext(
                task.opaqueCaseId(), family.id(), PRODUCER,
                inventoryIds(task.primitiveEngine()), family.split());
            runs.add(collector.finish(problem, result, context));
            tasks.put(task.opaqueCaseId(), task);
        }
        SearchTrajectoryDataset dataset = new SearchTrajectoryDataset(runs);
        assertTrue(dataset.leakageFree(), dataset.leakageViolations().toString());
        return new Study(tasks, runs, dataset);
    }

    private CaseEvidence evaluateCase(
        RuntimeTask task,
        SearchTrajectoryContext context,
        Map<Mode, DescriptorPolicyModel> models,
        InMemorySearchExperienceRepository experiences
    ) {
        GoalSearchResult staticResult = new BestFirstSearchStrategy()
            .searchWithDiagnostics(problem(task));
        List<Outcome> outcomes = new ArrayList<>();
        outcomes.add(Outcome.from("static", staticResult, List.of(), staticResult));
        for (Mode mode : Mode.values()) {
            DescriptorSearchPolicy policy = new DescriptorSearchPolicy(models.get(mode));
            PolicySearchResult result = new PolicyAwareBestFirstSearchStrategy(policy)
                .searchWithDiagnostics(problem(task));
            outcomes.add(Outcome.from(
                "descriptor-" + mode.name().toLowerCase(),
                result.search(), result.policyEvents(), staticResult));
        }
        DescriptorSearchPolicy experiencePolicy = new DescriptorSearchPolicy(
            models.get(Mode.LINEAR), experiences, "family-a");
        PolicySearchResult experienceResult = new PolicyAwareBestFirstSearchStrategy(experiencePolicy)
            .searchWithDiagnostics(problem(task));
        outcomes.add(Outcome.from(
            "descriptor-linear-experience",
            experienceResult.search(), experienceResult.policyEvents(), staticResult));
        return new CaseEvidence(
            task.opaqueCaseId(), context.family(), context.split().name(), outcomes);
    }

    private SearchProblem problem(RuntimeTask task) {
        return new SearchProblem(
            task.inputExpression(), task.primitiveEngine(), scorer, canonicalizer, task.heuristic())
            .withTarget(task.target());
    }

    private static List<String> inventoryIds(TransformationEngine engine) {
        Set<String> ids = new TreeSet<>();
        collectInventory(engine, ids);
        return List.copyOf(ids);
    }

    private static void collectInventory(TransformationEngine engine, Set<String> ids) {
        if (engine instanceof AstRewriteTransformationEngine ast) {
            ast.rules().forEach(rule -> ids.add(rule.id()));
        } else if (engine instanceof HypothesisTransformationEngine hypothesis) {
            collectInventory(hypothesis.baseEngine(), ids);
            hypothesis.operators().forEach(operator ->
                ids.add("hypothesis:" + operator.getClass().getSimpleName()));
        } else {
            ids.add("engine:" + engine.getClass().getName());
        }
    }

    private static String report(
        SearchTrajectoryDataset dataset,
        Map<Mode, DescriptorPolicyModel> models,
        int storedExperiences,
        List<CaseEvidence> cases,
        int nonFallbackEvents,
        int bestImprovement,
        boolean correctnessLoss,
        String conclusion,
        String limitingFeature
    ) {
        SearchTrajectoryDataset.DatasetSummary summary = dataset.summary();
        return new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("leakageFree", dataset.leakageFree())
            .property("familyCount", summary.families())
            .property("trainRuns", summary.splitBalances().get(DatasetSplit.TRAIN).runs())
            .property("validationRuns", summary.splitBalances().get(DatasetSplit.VALIDATION).runs())
            .property("testRuns", summary.splitBalances().get(DatasetSplit.TEST).runs())
            .property("featureSchema", DescriptorPolicyModel.FEATURE_SCHEMA)
            .property("sourceDatasetHash", models.get(Mode.LINEAR).sourceDatasetHash())
            .property("predictiveDatasetHash", models.get(Mode.LINEAR).predictiveDatasetHash())
            .property("storedExperiences", storedExperiences)
            .property("heldOutNonFallbackEvents", nonFallbackEvents)
            .property("requiredImprovementPermille", REQUIRED_IMPROVEMENT_PERMILLE)
            .property("bestImprovementPermille", bestImprovement)
            .property("correctnessLoss", correctnessLoss)
            .property("conclusion", conclusion)
            .property("limitingFeature", limitingFeature)
            .array("models", array -> models.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> array.objectValue(object -> object
                    .property("mode", entry.getKey().name())
                    .property("modelVersion", entry.getValue().modelVersion()))))
            .array("cases", array -> cases.forEach(caseEvidence ->
                array.objectValue(caseEvidence::writeJson)))
            .endObject()
            .toString();
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

    private record Study(
        Map<String, RuntimeTask> tasks,
        List<SearchTrajectoryRun> runs,
        SearchTrajectoryDataset dataset
    ) {
        private Study {
            tasks = Map.copyOf(tasks);
            runs = List.copyOf(runs);
        }
    }

    private record CaseEvidence(
        String caseId,
        String family,
        String split,
        List<Outcome> outcomes
    ) {
        private CaseEvidence {
            outcomes = List.copyOf(outcomes);
        }

        private void writeJson(JsonWriter json) {
            json.property("caseId", caseId)
                .property("family", family)
                .property("split", split)
                .array("outcomes", array -> outcomes.forEach(outcome ->
                    array.objectValue(outcome::writeJson)));
        }
    }

    private record Outcome(
        String policy,
        boolean reached,
        String status,
        int exploredStates,
        int improvementPermille,
        int fallbackEvents,
        int nonFallbackEvents,
        boolean correctnessLoss,
        List<String> selectedRules,
        List<RankingEvent> events
    ) {
        private Outcome {
            selectedRules = List.copyOf(selectedRules);
            events = List.copyOf(events);
        }

        private static Outcome from(
            String policy,
            GoalSearchResult result,
            List<RankingEvent> events,
            GoalSearchResult staticResult
        ) {
            int explored = result.metrics().exploredStates();
            int baseline = staticResult.metrics().exploredStates();
            int improvement = baseline == 0 ? 0 : (baseline - explored) * 1000 / baseline;
            int fallback = (int) events.stream().filter(RankingEvent::fallback).count();
            List<String> selected = result.reachedState() == null
                ? List.of()
                : result.reachedState().appliedRuleIds();
            return new Outcome(
                policy,
                result.reached(),
                result.status().name(),
                explored,
                improvement,
                fallback,
                events.size() - fallback,
                staticResult.reached() && !result.reached(),
                selected,
                events);
        }

        private void writeJson(JsonWriter json) {
            json.property("policy", policy)
                .property("reached", reached)
                .property("status", status)
                .property("exploredStates", exploredStates)
                .property("exploredImprovementPermille", improvementPermille)
                .property("fallbackEvents", fallbackEvents)
                .property("nonFallbackEvents", nonFallbackEvents)
                .property("correctnessLoss", correctnessLoss)
                .stringArray("selectedRules", selectedRules)
                .array("decisions", array -> events.forEach(event ->
                    array.objectValue(object -> object
                        .property("decisionGroup", event.decisionGroup())
                        .property("ruleId", event.ruleId())
                        .property("rank", event.deterministicRank())
                        .property("confidencePermille", event.confidencePermille())
                        .property("fallback", event.fallback())
                        .property("consideredBySearch", event.consideredBySearch())
                        .property("admittedToFrontier", event.admittedToFrontier())
                        .property("admissionOutcome", event.admissionOutcome())
                        .array("contributions", contributions ->
                            event.contributions().forEach((name, value) ->
                                contributions.objectValue(contribution -> contribution
                                    .property("name", name)
                                    .property("value", value))))))));
        }
    }
}
