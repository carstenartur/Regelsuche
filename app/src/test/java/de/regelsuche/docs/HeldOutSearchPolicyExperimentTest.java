package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
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
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy.PolicySearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchObserver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Held-out-family evaluation for the explainable policy from #268.
 *
 * <p>Primitive searches are completed and frozen before family/split metadata is
 * attached. Hidden rule IDs and generalized reference templates never enter the
 * collector, dataset, model, policy context, or ranking features.</p>
 */
class HeldOutSearchPolicyExperimentTest {
    private static final String SCHEMA = "regelsuche.search-policy-held-out/v1";
    private static final String PRODUCER = "held-out-search-policy-experiment/v1";
    private static final String TRAIN_FAMILY = "opaque-family-train";
    private static final String VALIDATION_FAMILY = "opaque-family-validation";
    private static final String TEST_FAMILY = "opaque-family-test";
    private static final Path REPORT = Path.of(
        "build", "reports", "search-policy-held-out", "report.json");

    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    @Test
    void evaluatesPoliciesOnWholeHeldOutFamiliesAndWritesDeterministicEvidence() throws IOException {
        ExperimentReport first = runExperiment();
        ExperimentReport second = runExperiment();

        assertEquals(first.toJson(), second.toJson(),
            "the held-out policy evidence must be byte-deterministic");
        assertTrue(first.leakageFree());
        assertEquals(3, first.familyCount());
        assertTrue(first.evaluations().stream().anyMatch(evaluation ->
            evaluation.split() == DatasetSplit.TEST));
        assertTrue(first.evaluations().stream().anyMatch(evaluation ->
            evaluation.split() == DatasetSplit.VALIDATION));
        assertTrue(first.noCorrectnessLoss());
        assertTrue(first.materialImprovement() || !first.limitingFeature().isBlank(),
            "a negative result must identify the feature that blocked transfer");
        assertFalse(first.toJson().contains("hidden_"),
            "hidden rule IDs must not enter the evidence or trained models");

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, first.toJson() + "\n", StandardCharsets.UTF_8);
    }

    private ExperimentReport runExperiment() {
        List<FrozenRun> frozen = HiddenRulePilotRuntimeCatalog.tasks().stream()
            .sorted(Comparator.comparing(HiddenRulePilotRunner.RuntimeTask::opaqueCaseId))
            .map(this::collectPrimitiveRun)
            .toList();

        List<SearchTrajectoryRun> runs = frozen.stream()
            .map(this::labelAfterSearch)
            .toList();
        SearchTrajectoryDataset dataset = new SearchTrajectoryDataset(runs);
        assertTrue(dataset.leakageFree(), "pilot trajectory split contains exact/alpha leakage");

        SearchPolicyTrainer trainer = new SearchPolicyTrainer();
        SearchPolicyModel frequency = trainer.train(dataset, Mode.FREQUENCY, 1);
        SearchPolicyModel linear = trainer.train(dataset, Mode.LINEAR, 1);
        SearchPolicyModel experienceModel = trainer.train(dataset, Mode.LINEAR_WITH_EXPERIENCE, 1);
        Map<String, SearchPolicyModel> models = orderedModels(frequency, linear, experienceModel);
        String modelMaterial = models.values().stream()
            .map(SearchPolicyModel::toPortableText)
            .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(modelMaterial.contains("hidden_"));

        InMemorySearchExperienceRepository experiences = new InMemorySearchExperienceRepository();
        runs.stream()
            .filter(run -> run.context().split() == DatasetSplit.TRAIN)
            .forEach(experiences::store);

        List<CaseEvaluation> evaluations = frozen.stream()
            .filter(run -> run.split() == DatasetSplit.VALIDATION
                || run.split() == DatasetSplit.TEST)
            .map(run -> evaluateHeldOut(run, models, experiences))
            .toList();

        boolean noCorrectnessLoss = evaluations.stream()
            .allMatch(CaseEvaluation::noCorrectnessLoss);
        int bestImprovement = evaluations.stream()
            .flatMap(evaluation -> evaluation.variants().stream())
            .filter(variant -> !variant.variant().equals("static"))
            .mapToInt(VariantEvaluation::exploredImprovementPermille)
            .max()
            .orElse(0);
        boolean material = noCorrectnessLoss && bestImprovement >= 200;
        String limitingFeature = material ? "" : limitingFeature(evaluations);

        return new ExperimentReport(
            SCHEMA,
            PRODUCER,
            dataset.leakageFree(),
            3,
            frequency.datasetHash(),
            frequency.featureSchemaVersion(),
            frequency.ruleInventoryHash(),
            models,
            evaluations,
            noCorrectnessLoss,
            material,
            bestImprovement,
            limitingFeature);
    }

    private FrozenRun collectPrimitiveRun(HiddenRulePilotRunner.RuntimeTask task) {
        SearchTrajectoryCollector collector = new SearchTrajectoryCollector();
        List<SearchEvent> rawEvents = new ArrayList<>();
        SearchObserver observer = event -> {
            rawEvents.add(event);
            collector.onEvent(event);
        };
        SearchProblem problem = problem(task).withObserver(observer);
        GoalSearchResult result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertTrue(result.reached(), () -> task.opaqueCaseId() + ": " + result);
        return new FrozenRun(
            task,
            problem,
            result,
            collector,
            observedRuleIds(rawEvents),
            split(task.opaqueCaseId()),
            opaqueFamily(task.opaqueCaseId()),
            evaluationFamily(task.opaqueCaseId()));
    }

    private SearchTrajectoryRun labelAfterSearch(FrozenRun frozen) {
        return frozen.collector().finish(
            frozen.problem(),
            frozen.result(),
            new SearchTrajectoryContext(
                "held-out-" + frozen.task().opaqueCaseId(),
                frozen.opaqueFamily(),
                PRODUCER,
                frozen.observedRuleIds(),
                frozen.split()));
    }

    private CaseEvaluation evaluateHeldOut(
        FrozenRun frozen,
        Map<String, SearchPolicyModel> models,
        InMemorySearchExperienceRepository experiences
    ) {
        GoalSearchResult staticResult = new BestFirstSearchStrategy()
            .searchWithDiagnostics(problem(frozen.task()));
        List<VariantEvaluation> variants = new ArrayList<>();
        variants.add(VariantEvaluation.staticResult(staticResult));
        models.forEach((name, model) -> {
            EmpiricalSearchPolicy policy = name.equals("linear-with-experience")
                ? new EmpiricalSearchPolicy(model, experiences, TRAIN_FAMILY)
                : new EmpiricalSearchPolicy(model);
            PolicySearchResult result = new PolicyAwareBestFirstSearchStrategy(policy)
                .searchWithDiagnostics(problem(frozen.task()));
            variants.add(VariantEvaluation.policyResult(name, staticResult, result));
        });
        variants.sort(Comparator.comparingInt(variant -> variantOrder(variant.variant())));
        return new CaseEvaluation(
            frozen.task().opaqueCaseId(),
            frozen.evaluationFamily(),
            frozen.split(),
            List.copyOf(variants));
    }

    private SearchProblem problem(HiddenRulePilotRunner.RuntimeTask task) {
        return new SearchProblem(
            task.inputExpression(),
            task.primitiveEngine(),
            scorer,
            canonicalizer,
            task.heuristic())
            .withTarget(task.target());
    }

    private static Map<String, SearchPolicyModel> orderedModels(
        SearchPolicyModel frequency,
        SearchPolicyModel linear,
        SearchPolicyModel experience
    ) {
        Map<String, SearchPolicyModel> models = new LinkedHashMap<>();
        models.put("frequency", frequency);
        models.put("linear", linear);
        models.put("linear-with-experience", experience);
        return Map.copyOf(models);
    }

    private static List<String> observedRuleIds(List<SearchEvent> events) {
        Set<String> rules = new LinkedHashSet<>();
        events.stream()
            .map(SearchEvent::ruleId)
            .filter(rule -> rule != null && !rule.isBlank())
            .sorted()
            .forEach(rules::add);
        if (rules.isEmpty()) {
            throw new IllegalStateException("primitive search emitted no rule decisions");
        }
        return List.copyOf(rules);
    }

    private static DatasetSplit split(String caseId) {
        return switch (caseId) {
            case "case-001", "case-003", "case-004" -> DatasetSplit.TRAIN;
            case "case-005" -> DatasetSplit.VALIDATION;
            case "case-002" -> DatasetSplit.TEST;
            default -> throw new IllegalArgumentException("unknown pilot case " + caseId);
        };
    }

    private static String opaqueFamily(String caseId) {
        return switch (split(caseId)) {
            case TRAIN -> TRAIN_FAMILY;
            case VALIDATION -> VALIDATION_FAMILY;
            case TEST -> TEST_FAMILY;
            case UNASSIGNED -> throw new IllegalStateException("pilot split must be assigned");
        };
    }

    private static String evaluationFamily(String caseId) {
        return switch (caseId) {
            case "case-001", "case-003", "case-004" -> "neutral-element-simplification";
            case "case-005" -> "power-normalization";
            case "case-002" -> "quartic-factorization";
            default -> throw new IllegalArgumentException("unknown pilot case " + caseId);
        };
    }

    private static int variantOrder(String variant) {
        return switch (variant) {
            case "static" -> 0;
            case "frequency" -> 1;
            case "linear" -> 2;
            case "linear-with-experience" -> 3;
            default -> 10;
        };
    }

    private static String limitingFeature(List<CaseEvaluation> evaluations) {
        boolean everyLearnedDecisionFellBack = evaluations.stream()
            .flatMap(evaluation -> evaluation.variants().stream())
            .filter(variant -> !variant.variant().equals("static"))
            .allMatch(VariantEvaluation::allPolicyDecisionsFallback);
        return everyLearnedDecisionFellBack
            ? "held-out primitive rule IDs have no training statistics; the model lacks rule-structural transfer features"
            : "available rule-ID and alpha-shape features did not improve the held-out primary metric";
    }

    private record FrozenRun(
        HiddenRulePilotRunner.RuntimeTask task,
        SearchProblem problem,
        GoalSearchResult result,
        SearchTrajectoryCollector collector,
        List<String> observedRuleIds,
        DatasetSplit split,
        String opaqueFamily,
        String evaluationFamily
    ) {
        private FrozenRun {
            observedRuleIds = List.copyOf(observedRuleIds);
        }
    }

    private record CaseEvaluation(
        String caseId,
        String family,
        DatasetSplit split,
        List<VariantEvaluation> variants
    ) {
        private CaseEvaluation {
            variants = List.copyOf(variants);
        }

        private boolean noCorrectnessLoss() {
            VariantEvaluation control = variants.stream()
                .filter(variant -> variant.variant().equals("static"))
                .findFirst().orElseThrow();
            return variants.stream().allMatch(variant ->
                !control.reached() || variant.reached());
        }
    }

    private record VariantEvaluation(
        String variant,
        boolean reached,
        String terminalStatus,
        int exploredStates,
        int expandedStates,
        int generatedTransformations,
        int enqueuedStates,
        int candidatePrunes,
        int depthPrunes,
        int duplicatePrunes,
        int transpositionPrunes,
        int pathLength,
        int exploredImprovementPermille,
        boolean allPolicyDecisionsFallback,
        List<String> selectedRuleTrace,
        List<String> explanations
    ) {
        private VariantEvaluation {
            selectedRuleTrace = List.copyOf(selectedRuleTrace);
            explanations = List.copyOf(explanations);
        }

        private static VariantEvaluation staticResult(GoalSearchResult result) {
            return from("static", result, result, List.of(), List.of(), true);
        }

        private static VariantEvaluation policyResult(
            String variant,
            GoalSearchResult control,
            PolicySearchResult policy
        ) {
            List<PolicyAwareBestFirstSearchStrategy.RankingEvent> selected = policy.policyEvents().stream()
                .filter(PolicyAwareBestFirstSearchStrategy.RankingEvent::selectedByCandidateBudget)
                .sorted(Comparator
                    .comparingLong(PolicyAwareBestFirstSearchStrategy.RankingEvent::decisionGroup)
                    .thenComparingInt(PolicyAwareBestFirstSearchStrategy.RankingEvent::deterministicRank))
                .toList();
            List<String> trace = selected.stream()
                .map(PolicyAwareBestFirstSearchStrategy.RankingEvent::ruleId)
                .toList();
            List<String> explanations = selected.stream()
                .map(event -> event.ruleId() + ":" + event.explanation())
                .toList();
            boolean fallback = !policy.policyEvents().isEmpty()
                && policy.policyEvents().stream()
                    .allMatch(PolicyAwareBestFirstSearchStrategy.RankingEvent::fallback);
            return from(variant, control, policy.search(), trace, explanations, fallback);
        }

        private static VariantEvaluation from(
            String variant,
            GoalSearchResult control,
            GoalSearchResult result,
            List<String> trace,
            List<String> explanations,
            boolean fallback
        ) {
            BestFirstSearchStrategy.GoalMetrics metrics = result.metrics();
            int improvement = control.metrics().exploredStates() == 0
                ? 0
                : (control.metrics().exploredStates() - metrics.exploredStates()) * 1000
                    / control.metrics().exploredStates();
            int pathLength = result.reachedState() == null
                ? -1
                : Math.max(0, result.reachedState().path().size() - 1);
            List<String> selectedTrace = trace.isEmpty() && result.reachedState() != null
                ? result.reachedState().appliedRuleIds()
                : trace;
            return new VariantEvaluation(
                variant,
                result.reached(),
                result.status().name(),
                metrics.exploredStates(),
                metrics.expandedStates(),
                metrics.generatedTransformations(),
                metrics.enqueuedStates(),
                metrics.candidateBudgetPrunes(),
                metrics.depthPrunes(),
                metrics.duplicatePrunes(),
                metrics.transpositionPrunes(),
                pathLength,
                improvement,
                fallback,
                selectedTrace,
                explanations);
        }
    }

    private record ExperimentReport(
        String schema,
        String producer,
        boolean leakageFree,
        int familyCount,
        String datasetHash,
        String featureSchema,
        String ruleInventoryHash,
        Map<String, SearchPolicyModel> models,
        List<CaseEvaluation> evaluations,
        boolean noCorrectnessLoss,
        boolean materialImprovement,
        int bestImprovementPermille,
        String limitingFeature
    ) {
        private ExperimentReport {
            Objects.requireNonNull(schema);
            Objects.requireNonNull(producer);
            models = Map.copyOf(models);
            evaluations = List.copyOf(evaluations);
            limitingFeature = limitingFeature == null ? "" : limitingFeature;
        }

        private String toJson() {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", schema)
                .property("producer", producer)
                .property("leakageFree", leakageFree)
                .property("familyCount", familyCount)
                .property("datasetHash", datasetHash)
                .property("featureSchema", featureSchema)
                .property("ruleInventoryHash", ruleInventoryHash)
                .property("noCorrectnessLoss", noCorrectnessLoss)
                .property("materialImprovement", materialImprovement)
                .property("bestImprovementPermille", bestImprovementPermille)
                .property("limitingFeature", limitingFeature)
                .array("models", array -> models.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> array.objectValue(object -> object
                        .property("variant", entry.getKey())
                        .property("modelVersion", entry.getValue().modelVersion())
                        .property("mode", entry.getValue().mode().name())
                        .property("minimumObservations", entry.getValue().minimumObservations()))))
                .array("evaluations", array -> evaluations.stream()
                    .sorted(Comparator.comparing(CaseEvaluation::caseId))
                    .forEach(evaluation -> array.objectValue(object -> object
                        .property("caseId", evaluation.caseId())
                        .property("family", evaluation.family())
                        .property("split", evaluation.split().name())
                        .array("variants", variants -> evaluation.variants().forEach(variant ->
                            variants.objectValue(value -> value
                                .property("variant", variant.variant())
                                .property("reached", variant.reached())
                                .property("terminalStatus", variant.terminalStatus())
                                .property("exploredStates", variant.exploredStates())
                                .property("expandedStates", variant.expandedStates())
                                .property("generatedTransformations", variant.generatedTransformations())
                                .property("enqueuedStates", variant.enqueuedStates())
                                .property("candidatePrunes", variant.candidatePrunes())
                                .property("depthPrunes", variant.depthPrunes())
                                .property("duplicatePrunes", variant.duplicatePrunes())
                                .property("transpositionPrunes", variant.transpositionPrunes())
                                .property("pathLength", variant.pathLength())
                                .property("exploredImprovementPermille", variant.exploredImprovementPermille())
                                .property("allPolicyDecisionsFallback", variant.allPolicyDecisionsFallback())
                                .array("selectedRuleTrace", trace -> variant.selectedRuleTrace()
                                    .forEach(trace::value))
                                .array("explanations", explanations -> variant.explanations()
                                    .forEach(explanations::value))))))))
                .endObject();
            return json.toString();
        }
    }
}
