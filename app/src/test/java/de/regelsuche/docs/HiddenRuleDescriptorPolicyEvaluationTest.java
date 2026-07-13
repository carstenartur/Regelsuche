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
import de.regelsuche.search.policy.DescriptorPolicyTrainer;
import de.regelsuche.search.policy.DescriptorSearchPolicy;
import de.regelsuche.search.policy.EmpiricalSearchPolicy;
import de.regelsuche.search.policy.SearchPolicyModel;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Real held-out-family evaluation for the rule-independent policy slice from #281. */
class HiddenRuleDescriptorPolicyEvaluationTest {
    private static final String SCHEMA =
        "regelsuche.hidden-rule-descriptor-policy-evaluation/v1";
    private static final String PRODUCER = "hidden-rule-descriptor-policy-evaluation/v1";
    private static final int REQUIRED_IMPROVEMENT_PERMILLE = 200;
    private static final String NEXT_DESCRIPTOR_BOUNDARY =
        "normalized local delta shape and affected-occurrence role/depth";

    private static final Map<String, FamilySpec> FAMILIES = Map.of(
        "case-001", new FamilySpec("family-a", DatasetSplit.TRAIN),
        "case-003", new FamilySpec("family-a", DatasetSplit.TRAIN),
        "case-004", new FamilySpec("family-a", DatasetSplit.TRAIN),
        "case-002", new FamilySpec("family-b", DatasetSplit.VALIDATION),
        "case-005", new FamilySpec("family-c", DatasetSplit.TEST));

    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    @Test
    void evaluatesUnseenRuleIdsWithDeterministicDescriptorEvidence() {
        EvaluationReport report = evaluate();
        EvaluationReport replay = evaluate();
        String json = report.toJson();
        Path output = Path.of(
            "build", "reports", "held-out-descriptor-policy", "report.json");
        Path datasetOutput = Path.of(
            "build", "reports", "held-out-descriptor-policy", "trajectories.jsonl");
        write(output, json);
        write(datasetOutput, report.datasetJsonLines());

        assertEquals(SCHEMA, report.schema());
        assertTrue(report.leakageFree());
        assertEquals(3, report.familyCount());
        assertEquals(3, report.trainRuns());
        assertEquals(1, report.validationRuns());
        assertEquals(1, report.testRuns());
        assertEquals(2, report.cases().size());
        assertFalse(report.correctnessLoss());
        assertEquals(5, report.heldOutDescriptorRuleIds(),
            "all five unseen pilot rule ids should receive descriptor evidence");
        assertTrue(report.cases().stream()
            .map(CaseReport::outcome)
            .map(outcomes -> outcomes.get("per-rule-frequency"))
            .allMatch(PolicyOutcome::completeFallback),
            "the concrete-rule baseline must remain unable to score unseen ids");
        assertTrue(report.cases().stream()
            .flatMap(caseReport -> caseReport.outcomes().stream())
            .filter(outcome -> outcome.policy().startsWith("descriptor-linear"))
            .anyMatch(outcome -> outcome.nonFallbackEvents() > 0));

        assertFalse(report.candidatePressureObserved(),
            "the original pilot budgets admit every candidate and cannot test ordering benefit");
        assertEquals(0, report.bestImprovementPermille());
        assertEquals("NEGATIVE_NO_CANDIDATE_PRESSURE", report.conclusion());
        assertFalse(report.experimentalLimitation().isBlank());
        assertEquals(NEXT_DESCRIPTOR_BOUNDARY, report.nextDescriptorBoundary());

        assertEquals(json, replay.toJson(), "the report must be byte-deterministic");
        assertEquals(report.datasetJsonLines(), replay.datasetJsonLines(),
            "the exported trajectory data must be byte-deterministic");
        assertTrue(Files.isRegularFile(output));
        assertTrue(Files.isRegularFile(datasetOutput));
        assertFalse(json.contains("hidden_"));
        assertFalse(json.contains("Gallery"));
    }

    private EvaluationReport evaluate() {
        Corpus corpus = collectCorpus();
        SearchTrajectoryDataset dataset = new SearchTrajectoryDataset(corpus.runs());
        assertTrue(dataset.leakageFree(), dataset.leakageViolations().toString());

        SearchPolicyModel perRuleFrequency = new SearchPolicyTrainer().train(
            dataset, SearchPolicyModel.Mode.FREQUENCY, 1);
        DescriptorPolicyTrainer descriptorTrainer = new DescriptorPolicyTrainer();
        DescriptorPolicyModel descriptorFrequency = descriptorTrainer.train(
            dataset, DescriptorPolicyModel.Mode.FREQUENCY, 1);
        DescriptorPolicyModel descriptorLinear = descriptorTrainer.train(
            dataset, DescriptorPolicyModel.Mode.LINEAR, 1);
        InMemorySearchExperienceRepository experiences = trainExperiences(corpus.runs());
        List<String> trainingFamilies = trainingFamilies(corpus.runs());

        List<CaseReport> cases = corpus.runs().stream()
            .filter(run -> run.context().split() != DatasetSplit.TRAIN)
            .sorted(Comparator.comparing(run -> run.context().runId()))
            .map(run -> evaluateCase(
                corpus.tasks().get(run.context().runId()),
                run.context(),
                perRuleFrequency,
                descriptorFrequency,
                descriptorLinear,
                experiences,
                trainingFamilies))
            .toList();

        int bestImprovement = cases.stream()
            .flatMap(caseReport -> caseReport.outcomes().stream())
            .filter(outcome -> outcome.policy().startsWith("descriptor-"))
            .mapToInt(PolicyOutcome::exploredImprovementPermille)
            .max()
            .orElse(0);
        boolean correctnessLoss = cases.stream()
            .flatMap(caseReport -> caseReport.outcomes().stream())
            .anyMatch(PolicyOutcome::correctnessLoss);
        int descriptorRuleIds = heldOutDescriptorRuleIds(cases);
        boolean candidatePressure = cases.stream()
            .flatMap(caseReport -> caseReport.outcomes().stream())
            .anyMatch(outcome -> outcome.candidatePrunes() > 0);
        boolean materialImprovement = bestImprovement >= REQUIRED_IMPROVEMENT_PERMILLE
            && !correctnessLoss;
        String conclusion = materialImprovement
            ? "POSITIVE_MATERIAL_IMPROVEMENT"
            : candidatePressure
                ? "NEGATIVE_LIMITING_DESCRIPTOR"
                : "NEGATIVE_NO_CANDIDATE_PRESSURE";
        String experimentalLimitation = candidatePressure
            ? ""
            : "all applicable held-out transformations are admitted under the current budgets, "
                + "so candidate ordering cannot change the explored state set";
        String nextDescriptorBoundary = materialImprovement
            ? ""
            : descriptorRuleIds == 0
                ? "broader TRAIN coverage for held-out descriptor ranges"
                : NEXT_DESCRIPTOR_BOUNDARY;

        SearchTrajectoryDataset.DatasetSummary summary = dataset.summary();
        return new EvaluationReport(
            SCHEMA,
            dataset.leakageFree(),
            summary.families(),
            summary.splitBalances().get(DatasetSplit.TRAIN).runs(),
            summary.splitBalances().get(DatasetSplit.VALIDATION).runs(),
            summary.splitBalances().get(DatasetSplit.TEST).runs(),
            perRuleFrequency.datasetHash(),
            perRuleFrequency.modelVersion(),
            descriptorLinear.sourceDatasetHash(),
            descriptorLinear.predictiveDatasetHash(),
            descriptorLinear.featureSchemaVersion(),
            descriptorFrequency.modelVersion(),
            descriptorLinear.modelVersion(),
            experiences.summary().total(),
            descriptorRuleIds,
            candidatePressure,
            bestImprovement,
            correctnessLoss,
            conclusion,
            experimentalLimitation,
            nextDescriptorBoundary,
            cases,
            dataset.toJsonLines());
    }

    private Corpus collectCorpus() {
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
            assertTrue(result.reached(), () ->
                task.opaqueCaseId() + " did not reach its public endpoint");
            runs.add(collector.finish(
                problem,
                result,
                new SearchTrajectoryContext(
                    task.opaqueCaseId(),
                    family.id(),
                    PRODUCER,
                    inventoryIds(task.primitiveEngine()),
                    family.split())));
            taskById.put(task.opaqueCaseId(), task);
        }
        return new Corpus(runs, taskById);
    }

    private static InMemorySearchExperienceRepository trainExperiences(
        List<SearchTrajectoryRun> runs
    ) {
        InMemorySearchExperienceRepository experiences = new InMemorySearchExperienceRepository();
        runs.stream()
            .filter(run -> run.context().split() == DatasetSplit.TRAIN)
            .forEach(experiences::store);
        return experiences;
    }

    private static List<String> trainingFamilies(List<SearchTrajectoryRun> runs) {
        return runs.stream()
            .filter(run -> run.context().split() == DatasetSplit.TRAIN)
            .map(run -> run.context().family())
            .distinct()
            .sorted()
            .toList();
    }

    private static int heldOutDescriptorRuleIds(List<CaseReport> cases) {
        return (int) cases.stream()
            .flatMap(caseReport -> caseReport.outcomes().stream())
            .filter(outcome -> outcome.policy().startsWith("descriptor-"))
            .flatMap(outcome -> outcome.decisions().stream())
            .filter(decision -> !decision.fallback())
            .map(DecisionEvidence::ruleId)
            .filter(ruleId -> !ruleId.isBlank())
            .distinct()
            .count();
    }

    private CaseReport evaluateCase(
        RuntimeTask task,
        SearchTrajectoryContext context,
        SearchPolicyModel perRuleFrequency,
        DescriptorPolicyModel descriptorFrequency,
        DescriptorPolicyModel descriptorLinear,
        InMemorySearchExperienceRepository experiences,
        List<String> trainingFamilies
    ) {
        GoalSearchResult staticResult = new BestFirstSearchStrategy()
            .searchWithDiagnostics(problem(task));
        List<PolicyOutcome> outcomes = new ArrayList<>();
        outcomes.add(PolicyOutcome.staticOutcome(staticResult));
        PolicySearchResult baseline = new PolicyAwareBestFirstSearchStrategy(
            new EmpiricalSearchPolicy(perRuleFrequency))
            .searchWithDiagnostics(problem(task));
        outcomes.add(PolicyOutcome.learned("per-rule-frequency", baseline, staticResult));
        outcomes.add(runDescriptor(
            "descriptor-frequency", task, descriptorFrequency, staticResult, null, List.of()));
        outcomes.add(runDescriptor(
            "descriptor-linear", task, descriptorLinear, staticResult, null, List.of()));
        outcomes.add(runDescriptor(
            "descriptor-linear-experience",
            task,
            descriptorLinear,
            staticResult,
            experiences,
            trainingFamilies));
        return new CaseReport(
            task.opaqueCaseId(),
            context.family(),
            context.split().name(),
            inventoryIds(task.primitiveEngine()),
            outcomes);
    }

    private PolicyOutcome runDescriptor(
        String name,
        RuntimeTask task,
        DescriptorPolicyModel model,
        GoalSearchResult staticResult,
        InMemorySearchExperienceRepository experiences,
        List<String> trainingFamilies
    ) {
        SearchProblem problem = problem(task);
        PolicySearchResult result;
        try (DescriptorSearchPolicy policy = new DescriptorSearchPolicy(
                model, problem, experiences, trainingFamilies)) {
            result = new PolicyAwareBestFirstSearchStrategy(policy)
                .searchWithDiagnostics(problem);
        }
        return PolicyOutcome.learned(name, result, staticResult);
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

    private record Corpus(
        List<SearchTrajectoryRun> runs,
        Map<String, RuntimeTask> tasks
    ) {
        private Corpus {
            runs = List.copyOf(runs);
            tasks = Map.copyOf(tasks);
        }
    }

    private record EvaluationReport(
        String schema,
        boolean leakageFree,
        int familyCount,
        int trainRuns,
        int validationRuns,
        int testRuns,
        String perRuleDatasetHash,
        String perRuleModelVersion,
        String descriptorSourceDatasetHash,
        String descriptorPredictiveDatasetHash,
        String descriptorFeatureSchema,
        String descriptorFrequencyModelVersion,
        String descriptorLinearModelVersion,
        int storedExperiences,
        int heldOutDescriptorRuleIds,
        boolean candidatePressureObserved,
        int bestImprovementPermille,
        boolean correctnessLoss,
        String conclusion,
        String experimentalLimitation,
        String nextDescriptorBoundary,
        List<CaseReport> cases,
        String datasetJsonLines
    ) {
        private EvaluationReport {
            cases = List.copyOf(cases);
        }

        private String toJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("leakageFree", leakageFree)
                .property("familyCount", familyCount)
                .property("trainRuns", trainRuns)
                .property("validationRuns", validationRuns)
                .property("testRuns", testRuns)
                .property("perRuleDatasetHash", perRuleDatasetHash)
                .property("perRuleModelVersion", perRuleModelVersion)
                .property("descriptorSourceDatasetHash", descriptorSourceDatasetHash)
                .property("descriptorPredictiveDatasetHash", descriptorPredictiveDatasetHash)
                .property("descriptorFeatureSchema", descriptorFeatureSchema)
                .property("descriptorFrequencyModelVersion", descriptorFrequencyModelVersion)
                .property("descriptorLinearModelVersion", descriptorLinearModelVersion)
                .property("storedExperiences", storedExperiences)
                .property("heldOutDescriptorRuleIds", heldOutDescriptorRuleIds)
                .property("candidatePressureObserved", candidatePressureObserved)
                .property("requiredImprovementPermille", REQUIRED_IMPROVEMENT_PERMILLE)
                .property("bestImprovementPermille", bestImprovementPermille)
                .property("correctnessLoss", correctnessLoss)
                .property("conclusion", conclusion)
                .property("experimentalLimitation", experimentalLimitation)
                .property("nextDescriptorBoundary", nextDescriptorBoundary)
                .array("cases", array -> cases.forEach(caseReport ->
                    array.objectValue(caseReport::writeJson)))
                .endObject()
                .toString();
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

        private Map<String, PolicyOutcome> outcome() {
            Map<String, PolicyOutcome> byName = new LinkedHashMap<>();
            outcomes.forEach(outcome -> byName.put(outcome.policy(), outcome));
            return Map.copyOf(byName);
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
        int nonFallbackEvents,
        boolean completeFallback,
        List<String> selectedRules,
        List<String> selectedExpressions,
        int exploredImprovementPermille,
        boolean correctnessLoss,
        List<DecisionEvidence> decisions
    ) {
        private PolicyOutcome {
            selectedRules = List.copyOf(selectedRules);
            selectedExpressions = List.copyOf(selectedExpressions);
            decisions = List.copyOf(decisions);
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
            int fallback = (int) events.stream().filter(RankingEvent::fallback).count();
            int nonFallback = events.size() - fallback;
            List<String> selectedRules = result.reachedState() == null
                ? List.of()
                : result.reachedState().appliedRuleIds();
            List<String> selectedExpressions = result.reachedState() == null
                ? List.of()
                : result.reachedState().path();
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
                result.reachedState() == null ? -1 : selectedRules.size(),
                events.size(),
                fallback,
                nonFallback,
                !events.isEmpty() && fallback == events.size(),
                selectedRules,
                selectedExpressions,
                improvement,
                staticResult.reached() && !result.reached(),
                events.stream().map(DecisionEvidence::from).toList());
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
                .property("nonFallbackEvents", nonFallbackEvents)
                .property("completeFallback", completeFallback)
                .stringArray("selectedRules", selectedRules)
                .stringArray("selectedExpressions", selectedExpressions)
                .property("exploredImprovementPermille", exploredImprovementPermille)
                .property("correctnessLoss", correctnessLoss)
                .array("decisions", array -> decisions.forEach(decision ->
                    array.objectValue(decision::writeJson)));
        }
    }

    private record DecisionEvidence(
        long decisionGroup,
        String parentExpression,
        String ruleId,
        String transformedExpression,
        int priority,
        int confidencePermille,
        boolean fallback,
        Map<String, Integer> contributions,
        String explanation,
        int deterministicRank,
        boolean consideredBySearch,
        boolean admittedToFrontier,
        String admissionOutcome
    ) {
        private DecisionEvidence {
            Map<String, Integer> sorted = new LinkedHashMap<>();
            contributions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            contributions = Map.copyOf(sorted);
        }

        private static DecisionEvidence from(RankingEvent event) {
            return new DecisionEvidence(
                event.decisionGroup(),
                event.parentExpression(),
                event.ruleId(),
                event.transformedExpression(),
                event.priority(),
                event.confidencePermille(),
                event.fallback(),
                event.contributions(),
                event.explanation(),
                event.deterministicRank(),
                event.consideredBySearch(),
                event.admittedToFrontier(),
                event.admissionOutcome());
        }

        private void writeJson(JsonWriter json) {
            json.property("decisionGroup", decisionGroup)
                .property("parentExpression", parentExpression)
                .property("ruleId", ruleId)
                .property("transformedExpression", transformedExpression)
                .property("priority", priority)
                .property("confidencePermille", confidencePermille)
                .property("fallback", fallback)
                .array("contributions", array -> contributions.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> array.objectValue(object -> object
                        .property("feature", entry.getKey())
                        .property("value", entry.getValue()))))
                .property("explanation", explanation)
                .property("deterministicRank", deterministicRank)
                .property("consideredBySearch", consideredBySearch)
                .property("admittedToFrontier", admittedToFrontier)
                .property("admissionOutcome", admissionOutcome);
        }
    }
}
