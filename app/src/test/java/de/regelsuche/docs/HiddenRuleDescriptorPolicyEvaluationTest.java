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
    void emitsDeterministicHeldOutDescriptorEvidence() {
        Study study = collectStudy();
        Map<Mode, DescriptorPolicyModel> models = train(study.dataset());
        InMemorySearchExperienceRepository experiences = new InMemorySearchExperienceRepository();
        study.runs().stream()
            .filter(run -> run.context().split() == DatasetSplit.TRAIN)
            .forEach(experiences::store);
        List<CaseEvidence> cases = study.runs().stream()
            .filter(run -> run.context().split() != DatasetSplit.TRAIN)
            .sorted(Comparator.comparing(run -> run.context().runId()))
            .map(run -> evaluateCase(study.tasks().get(run.context().runId()),
                run.context(), models, experiences))
            .toList();
        int nonFallback = cases.stream().flatMap(item -> item.outcomes().stream())
            .filter(item -> item.policy().startsWith("descriptor-linear"))
            .mapToInt(Outcome::nonFallbackEvents).sum();
        boolean correctnessLoss = cases.stream().flatMap(item -> item.outcomes().stream())
            .anyMatch(Outcome::correctnessLoss);
        int bestImprovement = cases.stream().flatMap(item -> item.outcomes().stream())
            .filter(item -> !item.policy().equals("static"))
            .mapToInt(Outcome::improvementPermille).max().orElse(0);
        String conclusion = bestImprovement >= REQUIRED_IMPROVEMENT_PERMILLE
                && !correctnessLoss
            ? "POSITIVE_MATERIAL_IMPROVEMENT"
            : "NEGATIVE_DESCRIPTOR_NO_MATERIAL_IMPROVEMENT";
        String limitation = conclusion.startsWith("POSITIVE") ? "none"
            : "shared structural deltas score held-out rules but do not improve the primary metric by 20 percent";
        String report = report(study.dataset(), models, experiences.size(), cases,
            nonFallback, bestImprovement, correctnessLoss, conclusion, limitation);
        Path directory = Path.of("build", "reports", "held-out-descriptor-policy");
        write(directory.resolve("report.json"), report);
        write(directory.resolve("trajectories.jsonl"), study.dataset().toJsonLines());

        assertTrue(study.dataset().leakageFree(), study.dataset().leakageViolations().toString());
        assertTrue(nonFallback > 0, "held-out rules require descriptor-derived scores");
        assertFalse(correctnessLoss);
        assertEquals(report, report(study.dataset(), models, experiences.size(), cases,
            nonFallback, bestImprovement, correctnessLoss, conclusion, limitation));
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
                task.opaqueCaseId(), family.id(), "descriptor-policy-evaluation/v1",
                inventoryIds(task.primitiveEngine()), family.split());
            runs.add(collector.finish(problem, result, context));
            tasks.put(task.opaqueCaseId(), task);
        }
        SearchTrajectoryDataset dataset = new SearchTrajectoryDataset(runs);
        assertTrue(dataset.leakageFree(), dataset.leakageViolations().toString());
        return new Study(tasks, runs, dataset);
    }

    private static Map<Mode, DescriptorPolicyModel> train(SearchTrajectoryDataset dataset) {
        DescriptorPolicyTrainer trainer = new DescriptorPolicyTrainer();
        Map<Mode, DescriptorPolicyModel> models = new EnumMap<>(Mode.class);
        for (Mode mode : Mode.values()) {
            models.put(mode, trainer.train(dataset, mode, 1));
        }
        return models;
    }

    private CaseEvidence evaluateCase(
        RuntimeTask task,
        SearchTrajectoryContext context,
        Map<Mode, DescriptorPolicyModel> models,
        InMemorySearchExperienceRepository experiences
    ) {
        GoalSearchResult baseline = new BestFirstSearchStrategy().searchWithDiagnostics(problem(task));
        List<Outcome> outcomes = new ArrayList<>();
        outcomes.add(Outcome.of("static", baseline, List.of(), baseline));
        for (Mode mode : Mode.values()) {
            PolicySearchResult result = run(
                new DescriptorSearchPolicy(models.get(mode)), task);
            outcomes.add(Outcome.of("descriptor-" + mode.name().toLowerCase(),
                result.search(), result.policyEvents(), baseline));
        }
        PolicySearchResult experience = run(new DescriptorSearchPolicy(
            models.get(Mode.LINEAR), experiences, "family-a"), task);
        outcomes.add(Outcome.of("descriptor-linear-experience",
            experience.search(), experience.policyEvents(), baseline));
        return new CaseEvidence(task.opaqueCaseId(), context.family(), context.split().name(), outcomes);
    }

    private PolicySearchResult run(DescriptorSearchPolicy policy, RuntimeTask task) {
        return new PolicyAwareBestFirstSearchStrategy(policy).searchWithDiagnostics(problem(task));
    }

    private SearchProblem problem(RuntimeTask task) {
        return new SearchProblem(task.inputExpression(), task.primitiveEngine(), scorer,
            canonicalizer, task.heuristic()).withTarget(task.target());
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
        int nonFallback,
        int bestImprovement,
        boolean correctnessLoss,
        String conclusion,
        String limitation
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
            .property("heldOutNonFallbackEvents", nonFallback)
            .property("requiredImprovementPermille", REQUIRED_IMPROVEMENT_PERMILLE)
            .property("bestImprovementPermille", bestImprovement)
            .property("correctnessLoss", correctnessLoss)
            .property("conclusion", conclusion)
            .property("limitingFeature", limitation)
            .array("models", array -> models.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> array.objectValue(object -> object
                    .property("mode", entry.getKey().name())
                    .property("modelVersion", entry.getValue().modelVersion()))))
            .array("cases", array -> cases.forEach(item -> array.objectValue(item::writeJson)))
            .endObject().toString();
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.toAbsolutePath().normalize().getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
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
            json.property("caseId", caseId).property("family", family).property("split", split)
                .array("outcomes", array -> outcomes.forEach(item ->
                    array.objectValue(item::writeJson)));
        }
    }

    private record Outcome(
        String policy,
        GoalSearchResult result,
        List<RankingEvent> events,
        int improvementPermille,
        boolean correctnessLoss
    ) {
        private Outcome {
            events = List.copyOf(events);
        }

        private static Outcome of(
            String policy,
            GoalSearchResult result,
            List<RankingEvent> events,
            GoalSearchResult baseline
        ) {
            int explored = result.metrics().exploredStates();
            int baselineExplored = baseline.metrics().exploredStates();
            int improvement = baselineExplored == 0 ? 0
                : (baselineExplored - explored) * 1000 / baselineExplored;
            return new Outcome(policy, result, events, improvement,
                baseline.reached() && !result.reached());
        }

        private int nonFallbackEvents() {
            return (int) events.stream().filter(event -> !event.fallback()).count();
        }

        private void writeJson(JsonWriter json) {
            List<String> selected = result.reachedState() == null ? List.of()
                : result.reachedState().appliedRuleIds();
            json.property("policy", policy)
                .property("reached", result.reached())
                .property("status", result.status().name())
                .property("exploredStates", result.metrics().exploredStates())
                .property("exploredImprovementPermille", improvementPermille)
                .property("fallbackEvents", events.size() - nonFallbackEvents())
                .property("nonFallbackEvents", nonFallbackEvents())
                .property("correctnessLoss", correctnessLoss)
                .stringArray("selectedRules", selected)
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
                        .stringArray("contributions", event.contributions().entrySet().stream()
                            .map(entry -> entry.getKey() + "=" + entry.getValue()).toList()))));
        }
    }
}
