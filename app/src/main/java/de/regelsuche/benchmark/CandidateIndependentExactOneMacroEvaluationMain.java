package de.regelsuche.benchmark;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.benchmark.CandidateIndependentExactOneMacroSelector.Selection;
import de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapter.ReplayEvidence;
import de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapter.ReplayTrace;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.EvaluationTask;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.FormationResult;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.MacroCandidate;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.PairedEvaluation;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.SearchRun;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.UtilityOutcome;
import de.regelsuche.discovery.TransformationStep;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Executes the frozen downstream stream with the baseline inventory and exactly
 * one candidate selected solely from TRAIN formation evidence.
 */
public final class CandidateIndependentExactOneMacroEvaluationMain {
    public static final String SCHEMA = "regelsuche.paired-task-utility/v1";
    private static final String BENCHMARK =
        "regelsuche-candidate-independent-autonomous-discovery-2026-07/v1";
    private static final String CHALLENGE = "reusable-search-macros";
    private static final String PROFILE_ID = "macro-primitives/v1";
    private static final String COMPARISON =
        "IDENTICAL_INPUT_TARGET_INVENTORY_STRATEGY_AND_BUDGET";
    private static final String INVENTORY_POLICY =
        "BASELINE_PLUS_EXACTLY_ONE_SELECTED_CANDIDATE";
    private static final int TASK_COUNT = 12;

    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    private CandidateIndependentExactOneMacroEvaluationMain() {
    }

    public static void main(String[] args) throws IOException {
        Arguments arguments = Arguments.parse(args);
        ObjectNode benchmark = readObject(arguments.benchmarkSource());
        ObjectNode corpus = readObject(arguments.corpus());
        ObjectNode profile = readObject(arguments.profile());
        ObjectNode receipt = readObject(arguments.freezeReceipt());
        validateFrozenInputs(benchmark, corpus, profile, receipt);

        List<ObjectNode> cases = macroCases(corpus);
        List<ReplayTrace> traces = trainTraces(cases);
        CandidateIndependentReusableMacroAdapter adapter =
            new CandidateIndependentReusableMacroAdapter(operationRules(profile));
        FormationResult formation = adapter.form(traces);
        Selection selection = CandidateIndependentExactOneMacroSelector
            .select(formation);
        require(selection.exactOneFormation().macros().size() == 1,
            "candidate-enabled inventory is not exact-one");

        ObjectNode stream = readObject(arguments.downstreamStream());
        validateStream(stream, corpus, profile, receipt);
        List<TaskBinding> tasks = streamTasks(stream);

        ArrayNode evaluations = JSON.createArrayNode();
        LinkedHashMap<String, Integer> outcomeCounts = emptyOutcomeCounts();
        ResourceAccumulator resources = new ResourceAccumulator(
            formation.replayEvidence());
        int correctnessRegressions = 0;
        for (TaskBinding binding : tasks) {
            PairedEvaluation evaluation = adapter.evaluate(
                binding.task(), selection.exactOneFormation());
            outcomeCounts.merge(
                evaluation.outcome().name(), 1, Integer::sum);
            resources.add(evaluation);
            if (evaluation.correctnessRegression()) {
                correctnessRegressions++;
            }
            evaluations.add(evaluation(
                binding, evaluation, selection.candidate().macroId()));
        }
        require(outcomeCounts.values().stream().mapToInt(Integer::intValue).sum()
                == TASK_COUNT,
            "outcome accounting does not retain every frozen task");
        require(correctnessRegressions == 0,
            "exact-one candidate caused a correctness regression");

        ObjectNode budgets = requireObjectField(
            benchmark, "budgets", "benchmark budgets");
        ObjectNode resourceUse = resources.resourceUse(budgets);
        ArrayNode formedCandidates = JSON.createArrayNode();
        String selectedCandidateContentHash = null;
        for (MacroCandidate candidate : formation.macros().stream()
                .sorted(Comparator.comparing(MacroCandidate::macroId)).toList()) {
            ObjectNode retained = candidate(candidate);
            formedCandidates.add(retained);
            if (candidate.macroId().equals(selection.candidate().macroId())) {
                selectedCandidateContentHash = text(retained, "contentHash");
            }
        }
        require(selectedCandidateContentHash != null,
            "selected candidate is absent from the formed-candidate ledger");

        ObjectNode selectionNode = JSON.createObjectNode();
        selectionNode.put("policy", selection.policy());
        selectionNode.put("selectedCandidateId",
            selection.candidate().macroId());
        selectionNode.put("selectedCandidateContentHash",
            selectedCandidateContentHash);
        selectionNode.put("selectedTrainSupport",
            selection.candidate().supportingTraceIds().size());
        selectionNode.put("formedCandidateCount", formation.macros().size());
        addContentHash(selectionNode);

        ArrayNode replayEvidence = JSON.createArrayNode();
        formation.replayEvidence().stream()
            .sorted(Comparator.comparing(ReplayEvidence::traceId))
            .map(CandidateIndependentExactOneMacroEvaluationMain::replayEvidence)
            .forEach(replayEvidence::add);

        ObjectNode run = JSON.createObjectNode();
        run.put("schema", SCHEMA);
        run.put("benchmarkId", BENCHMARK);
        run.put("challengeId", CHALLENGE);
        run.put("repositoryRevision", arguments.repositoryRevision());
        run.put("caseCorpusContentHash", text(corpus, "contentHash"));
        run.put("freezeReceiptContentHash", text(receipt, "contentHash"));
        run.put("combinedPreregistrationHash",
            text(receipt, "combinedPreregistrationHash"));
        run.put("downstreamTaskStreamContentHash",
            text(stream, "contentHash"));
        run.put("baselineInventoryProfileId", PROFILE_ID);
        run.put("baselineInventoryContentHash", text(profile, "contentHash"));
        run.put("comparisonPolicy", COMPARISON);
        run.put("formationAccessPolicy", "TRAIN_ONLY");
        run.put("candidateSelectionPolicy", selection.policy());
        run.put("candidateInventoryPolicy", INVENTORY_POLICY);
        run.put("formedCandidateCount", formation.macros().size());
        run.put("enabledCandidateCount", 1);
        run.set("formationReplayEvidence", replayEvidence);
        run.set("formedCandidates", formedCandidates);
        run.set("candidateSelection", selectionNode);
        run.put("configuredTasks", TASK_COUNT);
        run.put("executedTasks", evaluations.size());
        run.set("aggregateOutcomeCounts", object(outcomeCounts));
        run.put("correctnessRegressionCount", correctnessRegressions);
        run.set("resourceUse", resourceUse);
        run.put("formalProofStatus", "NOT_EVALUATED");
        run.put("externalNoveltyStatus", "NOT_EVALUATED");
        run.put("publicationAuthorized", false);
        run.set("tasks", evaluations);
        addContentHash(run);

        Path output = arguments.output().toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(
            output,
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run)
                + "\n",
            StandardCharsets.UTF_8);
        System.out.println("pairedTaskUtility=" + output);
        System.out.println("contentHash=" + text(run, "contentHash"));
        System.out.println("selectedCandidate="
            + selection.candidate().macroId());
        System.out.println("aggregateOutcomeCounts=" + outcomeCounts);
    }

    private static ObjectNode evaluation(
        TaskBinding binding,
        PairedEvaluation evaluation,
        String candidateId
    ) {
        ObjectNode result = JSON.createObjectNode();
        result.put("index", binding.index());
        result.put("taskId", evaluation.taskId());
        result.put("caseId", binding.caseId());
        result.put("split", binding.split());
        result.put("structuralCluster", binding.structuralCluster());
        result.put("streamTaskContentHash", binding.streamTaskContentHash());
        result.put("source", binding.task().source());
        result.put("target", binding.task().target());
        result.set("assumptions", strings(binding.task().assumptions()));
        result.set("searchBudget", object(Map.of(
            "maxDepth", binding.task().maxDepth(),
            "maxExpandedStates", binding.task().maxExpandedStates())));
        result.put("comparisonPolicy", COMPARISON);
        result.put("candidateInventoryPolicy", INVENTORY_POLICY);
        result.set("enabledCandidateIds", strings(List.of(candidateId)));
        result.put("outcome", evaluation.outcome().name());
        result.put("correctnessRegression",
            evaluation.correctnessRegression());
        result.set("baseline", searchRun(evaluation.baseline()));
        result.set("candidateEnabled",
            searchRun(evaluation.macroEnabled()));
        result.set("resourceDelta", resourceDelta(
            evaluation.baseline(), evaluation.macroEnabled()));
        result.put("detail", evaluation.detail());
        addContentHash(result);
        return result;
    }

    private static ObjectNode searchRun(SearchRun run) {
        ObjectNode result = JSON.createObjectNode();
        result.put("success", run.success());
        result.put("reachedExpression", run.reachedExpression());
        result.set("path", strings(run.path()));
        result.set("ruleIds", strings(run.ruleIds()));
        result.put("expandedStates", run.expandedStates());
        result.put("generatedCandidates", run.generatedCandidates());
        result.put("budgetExhausted", run.budgetExhausted());
        result.put("detail", run.detail());
        addContentHash(result);
        return result;
    }

    private static ObjectNode resourceDelta(
        SearchRun baseline,
        SearchRun candidate
    ) {
        ObjectNode result = JSON.createObjectNode();
        result.put("expandedStateSaving",
            baseline.expandedStates() - candidate.expandedStates());
        result.put("generatedCandidateSaving",
            baseline.generatedCandidates() - candidate.generatedCandidates());
        result.put("pathStepSaving",
            baseline.ruleIds().size() - candidate.ruleIds().size());
        addContentHash(result);
        return result;
    }

    private static ObjectNode candidate(MacroCandidate candidate) {
        ObjectNode result = JSON.createObjectNode();
        result.put("macroId", candidate.macroId());
        result.set("operationSequence",
            strings(candidate.operationSequence()));
        result.set("supportingTraceIds",
            strings(candidate.supportingTraceIds()));
        result.put("trainSupport", candidate.supportingTraceIds().size());
        result.put("leftPattern", candidate.rule().leftPattern());
        result.put("rightPattern", candidate.rule().rightPattern());
        result.set("parameterRelations",
            strings(candidate.rule().parameterRelations()));
        result.set("assumptions", strings(candidate.rule().assumptions()));
        result.put("proofStatus", candidate.rule().proofStatus().name());
        result.put("knownRuleStatus",
            candidate.rule().knownRuleStatus().name());
        result.put("supportingExamples",
            candidate.rule().supportingExamples());
        result.put("occurrenceCount", candidate.rule().occurrenceCount());
        result.put("confidenceScore", candidate.rule().confidenceScore());
        result.put("canonicalHash", candidate.rule().canonicalHash());
        result.put("validationEvidence", candidate.validationEvidence());
        ArrayNode steps = JSON.createArrayNode();
        candidate.atomicSteps().stream()
            .sorted(Comparator.comparingInt(TransformationStep::index))
            .map(CandidateIndependentExactOneMacroEvaluationMain::atomicStep)
            .forEach(steps::add);
        result.set("atomicSteps", steps);
        addContentHash(result);
        return result;
    }

    private static ObjectNode atomicStep(TransformationStep step) {
        ObjectNode result = JSON.createObjectNode();
        result.put("index", step.index());
        result.put("beforeExpression", step.beforeExpression());
        result.put("afterExpression", step.afterExpression());
        result.put("ruleId", step.ruleId());
        result.put("ruleKind", step.ruleKind().name());
        result.put("scoreBefore", step.scoreBefore());
        result.put("scoreAfter", step.scoreAfter());
        result.put("equivalencePreserving", step.equivalencePreserving());
        result.put("explanation", step.explanation());
        result.set("assumptions", strings(step.assumptions()));
        addContentHash(result);
        return result;
    }

    private static ObjectNode replayEvidence(ReplayEvidence evidence) {
        ObjectNode result = JSON.createObjectNode();
        result.put("traceId", evidence.traceId());
        result.put("reproduced", evidence.reproduced());
        result.put("exploredStates", evidence.exploredStates());
        result.set("actualRuleIds", strings(evidence.actualRuleIds()));
        result.set("assignedOperationIds",
            strings(evidence.assignedOperationIds()));
        result.set("compressedOperationIds",
            strings(evidence.compressedOperationIds()));
        result.set("expressionPath", strings(evidence.expressionPath()));
        result.put("detail", evidence.detail());
        addContentHash(result);
        return result;
    }

    private static List<ReplayTrace> trainTraces(List<ObjectNode> cases) {
        List<ReplayTrace> result = new ArrayList<>();
        for (ObjectNode benchmarkCase : cases) {
            if (!"TRAIN".equals(text(benchmarkCase, "split"))) {
                continue;
            }
            ObjectNode formation = requireObjectField(
                benchmarkCase, "formationInput", "macro TRAIN formationInput");
            require(!formation.path("heldOutTargetsVisible").asBoolean(true),
                "macro TRAIN formation exposes held-out targets");
            require(PROFILE_ID.equals(
                    text(formation, "primitiveInventoryProfile")),
                "macro TRAIN case uses an unexpected primitive profile");
            for (JsonNode item : requireArray(
                    formation, "replayTraces", "macro replay traces")) {
                ObjectNode trace = requireObject(item, "macro replay trace");
                result.add(new ReplayTrace(
                    text(trace, "traceId"),
                    text(trace, "source"),
                    text(trace, "target"),
                    stringValues(requireArray(
                        trace, "primitiveSteps", "trace primitive steps")),
                    stringValues(requireArray(
                        trace, "assumptions", "trace assumptions"))));
            }
        }
        result.sort(Comparator.comparing(ReplayTrace::traceId));
        require(result.size() == 4,
            "frozen macro TRAIN replay count changed: " + result.size());
        return List.copyOf(result);
    }

    private static List<TaskBinding> streamTasks(ObjectNode stream) {
        List<TaskBinding> result = new ArrayList<>();
        int expectedIndex = 1;
        for (JsonNode item : requireArray(
                stream, "tasks", "downstream stream tasks")) {
            ObjectNode task = requireObject(item, "downstream stream task");
            requireContentHash(task,
                "downstream stream task " + text(task, "taskId"));
            int index = integer(task, "index", "downstream stream task");
            require(index == expectedIndex,
                "downstream task order changed at index " + expectedIndex);
            expectedIndex++;
            ObjectNode budget = requireObjectField(
                task, "searchBudget", "downstream task budget");
            result.add(new TaskBinding(
                index,
                text(task, "taskId"),
                text(task, "caseId"),
                text(task, "split"),
                text(task, "structuralCluster"),
                text(task, "contentHash"),
                new EvaluationTask(
                    text(task, "taskId"),
                    text(task, "source"),
                    text(task, "target"),
                    stringValues(requireArray(
                        task, "assumptions", "downstream task assumptions")),
                    integer(budget, "maxDepth", "downstream task budget"),
                    integer(
                        budget,
                        "maxExpandedStates",
                        "downstream task budget"))));
        }
        require(result.size() == TASK_COUNT,
            "frozen downstream task count changed: " + result.size());
        return List.copyOf(result);
    }

    private static Map<String, List<String>> operationRules(
        ObjectNode profile
    ) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        for (JsonNode item : requireArray(
                profile, "operations", "macro profile operations")) {
            ObjectNode operation = requireObject(item, "macro operation");
            result.put(
                text(operation, "operationId"),
                stringValues(requireArray(
                    operation,
                    "implementationRuleIds",
                    "macro operation implementation rules")));
        }
        require(result.size() == 6,
            "macro primitive operation count changed: " + result.size());
        return Map.copyOf(result);
    }

    private static List<ObjectNode> macroCases(ObjectNode corpus) {
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode item : requireArray(corpus, "cases", "case corpus")) {
            ObjectNode benchmarkCase = requireObject(item, "benchmark case");
            if (!CHALLENGE.equals(text(benchmarkCase, "challengeId"))) {
                continue;
            }
            requireContentHash(benchmarkCase,
                "macro case " + text(benchmarkCase, "caseId"));
            validateExposure(benchmarkCase);
            result.add(benchmarkCase);
        }
        result.sort(Comparator.comparing(item -> text(item, "caseId")));
        require(result.stream()
                .map(item -> text(item, "caseId"))
                .toList()
                .equals(List.of(
                    "case-13", "case-14", "case-15",
                    "case-16", "case-17", "case-18")),
            "macro case identities changed");
        return List.copyOf(result);
    }

    private static void validateExposure(ObjectNode benchmarkCase) {
        String caseId = text(benchmarkCase, "caseId");
        String split = text(benchmarkCase, "split");
        ObjectNode policy = requireObjectField(
            benchmarkCase, "exposurePolicy", "macro exposure policy");
        List<String> mayRead = stringValues(requireArray(
            policy, "candidateFormationMayRead", "formation readable inputs"));
        List<String> mustNotRead = stringValues(requireArray(
            policy,
            "candidateFormationMustNotRead",
            "formation prohibited inputs"));
        require(mustNotRead.equals(List.of("evaluationInput")),
            "case " + caseId + " does not prohibit evaluation input");
        if ("TRAIN".equals(split)) {
            require(mayRead.equals(List.of("formationInput")),
                "TRAIN macro formation surface changed: " + caseId);
            require(benchmarkCase.path("formationInput").isObject(),
                "TRAIN macro case has no formation payload: " + caseId);
        } else {
            require(mayRead.isEmpty(),
                "held-out macro case exposes formation data: " + caseId);
            require(benchmarkCase.path("formationInput").isNull(),
                "held-out macro case has formation payload: " + caseId);
        }
    }

    private static void validateFrozenInputs(
        ObjectNode benchmark,
        ObjectNode corpus,
        ObjectNode profile,
        ObjectNode receipt
    ) {
        requireContentHash(corpus, "case corpus");
        requireContentHash(profile, "macro primitive profile");
        requireContentHash(receipt, "corpus freeze receipt");
        require(semanticHash(benchmark).equals(
                text(receipt, "benchmarkSourceContentHash")),
            "benchmark source is not bound by the freeze receipt");
        require(text(corpus, "contentHash").equals(
                text(receipt, "caseCorpusContentHash")),
            "case corpus is not bound by the freeze receipt");
        ObjectNode roots = requireObjectField(
            receipt,
            "formationInventoryContentHashes",
            "freeze receipt inventory roots");
        require(text(profile, "contentHash").equals(text(roots, PROFILE_ID)),
            "macro profile is not bound by the freeze receipt");
        require("NOT_STARTED".equals(text(benchmark, "executionStatus")),
            "benchmark source was modified after freeze");
        require("NOT_STARTED".equals(
                text(receipt, "executionStatusAtFreeze")),
            "corpus was not frozen before execution");
        require(integer(
                receipt, "executedCampaignsAtFreeze", "receipt") == 0,
            "freeze receipt already contains campaigns");
        require(integer(
                receipt, "executedEvaluationsAtFreeze", "receipt") == 0,
            "freeze receipt already contains evaluations");
        require(!receipt.path("publicationAuthorized").asBoolean(true),
            "freeze receipt unexpectedly authorizes publication");
    }

    private static void validateStream(
        ObjectNode stream,
        ObjectNode corpus,
        ObjectNode profile,
        ObjectNode receipt
    ) {
        requireContentHash(stream, "downstream task stream");
        require("regelsuche.downstream-task-stream/v1".equals(
                text(stream, "schema")),
            "unexpected downstream stream schema");
        require(BENCHMARK.equals(text(stream, "benchmarkId")),
            "downstream stream benchmark identity changed");
        require(CHALLENGE.equals(text(stream, "challengeId")),
            "downstream stream challenge identity changed");
        require(text(stream, "sourceCaseCorpusContentHash").equals(
                text(corpus, "contentHash")),
            "downstream stream is not bound to the case corpus");
        require(text(stream, "freezeReceiptContentHash").equals(
                text(receipt, "contentHash")),
            "downstream stream is not bound to the freeze receipt");
        require(text(stream, "baselineInventoryContentHash").equals(
                text(profile, "contentHash")),
            "downstream stream is not bound to the baseline inventory");
        require(text(stream, "combinedPreregistrationHash").equals(
                text(receipt, "combinedPreregistrationHash")),
            "downstream stream preregistration binding changed");
        require(COMPARISON.equals(text(stream, "comparisonPolicy")),
            "downstream stream comparison policy changed");
        require("EVALUATION_INPUT_HIDDEN_DURING_CANDIDATE_FORMATION".equals(
                text(stream, "formationAccessPolicy")),
            "downstream stream formation access policy changed");
        require(integer(stream, "configuredTasks", "downstream stream")
                == TASK_COUNT,
            "downstream stream task count changed");
        require("NOT_EXECUTED_BY_STREAM_CONSTRUCTION".equals(
                text(stream, "evaluationStatus")),
            "downstream stream already contains evaluated results");
        require(!stream.path("publicationAuthorized").asBoolean(true),
            "downstream stream unexpectedly authorizes publication");
    }

    private static LinkedHashMap<String, Integer> emptyOutcomeCounts() {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (UtilityOutcome outcome : UtilityOutcome.values()) {
            result.put(outcome.name(), 0);
        }
        return result;
    }

    private static ObjectNode readObject(Path path) throws IOException {
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path),
            "expected regular non-symbolic JSON file: " + path);
        return requireObject(
            JSON.readTree(Files.readString(path, StandardCharsets.UTF_8)),
            path.toString());
    }

    private static void requireContentHash(
        ObjectNode value,
        String context
    ) {
        String retained = text(value, "contentHash");
        ObjectNode material = value.deepCopy();
        material.remove("contentHash");
        String expected = semanticHash(material);
        require(retained.equals(expected),
            context + " contentHash mismatch: " + retained + " != "
                + expected);
    }

    private static void addContentHash(ObjectNode value) {
        require(!value.has("contentHash"), "contentHash already present");
        value.put("contentHash", semanticHash(value));
    }

    private static String semanticHash(JsonNode value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    canonicalJson(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String canonicalJson(JsonNode value) {
        try {
            if (value.isObject()) {
                TreeSet<String> fields = new TreeSet<>();
                value.fieldNames().forEachRemaining(fields::add);
                StringBuilder result = new StringBuilder("{");
                int index = 0;
                for (String field : fields) {
                    if (index++ > 0) {
                        result.append(',');
                    }
                    result.append(JSON.writeValueAsString(field))
                        .append(':')
                        .append(canonicalJson(value.get(field)));
                }
                return result.append('}').toString();
            }
            if (value.isArray()) {
                StringBuilder result = new StringBuilder("[");
                for (int index = 0; index < value.size(); index++) {
                    if (index > 0) {
                        result.append(',');
                    }
                    result.append(canonicalJson(value.get(index)));
                }
                return result.append(']').toString();
            }
            return JSON.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "cannot canonicalize JSON", exception);
        }
    }

    private static ObjectNode object(Map<String, ?> values) {
        return JSON.valueToTree(new LinkedHashMap<>(values));
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode result = JSON.createArrayNode();
        values.forEach(result::add);
        return result;
    }

    private static List<String> stringValues(ArrayNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            require(value.isTextual() && !value.asText().isBlank(),
                "expected nonblank string array item");
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    private static ObjectNode requireObject(
        JsonNode value,
        String context
    ) {
        require(value != null && value.isObject(),
            context + " is not an object");
        return (ObjectNode) value;
    }

    private static ObjectNode requireObjectField(
        JsonNode value,
        String field,
        String context
    ) {
        return requireObject(value.get(field), context);
    }

    private static ArrayNode requireArray(
        JsonNode value,
        String field,
        String context
    ) {
        JsonNode child = value.get(field);
        require(child != null && child.isArray(),
            context + " is not an array");
        return (ArrayNode) child;
    }

    private static String text(JsonNode value, String field) {
        Objects.requireNonNull(value, "value");
        JsonNode child = value.get(field);
        require(child != null && child.isTextual()
                && !child.asText().isBlank(),
            "missing textual field " + field);
        return child.asText();
    }

    private static int integer(
        JsonNode value,
        String field,
        String context
    ) {
        JsonNode child = value.get(field);
        require(child != null && child.canConvertToInt(),
            context + " has no integer field " + field);
        return child.intValue();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record TaskBinding(
        int index,
        String taskId,
        String caseId,
        String split,
        String structuralCluster,
        String streamTaskContentHash,
        EvaluationTask task
    ) {
        private TaskBinding {
            require(taskId.equals(task.taskId()),
                "task binding identity mismatch");
        }
    }

    private static final class ResourceAccumulator {
        private final int formationStates;
        private final int formationCandidateEvaluations;
        private int baselineStates;
        private int candidateStates;
        private int baselineCandidates;
        private int candidateCandidates;
        private int baselinePathSteps;
        private int candidatePathSteps;

        private ResourceAccumulator(List<ReplayEvidence> evidence) {
            formationStates = evidence.stream()
                .mapToInt(ReplayEvidence::exploredStates)
                .sum();
            formationCandidateEvaluations = evidence.stream()
                .mapToInt(item -> item.actualRuleIds().size())
                .sum();
        }

        private void add(PairedEvaluation evaluation) {
            baselineStates += evaluation.baseline().expandedStates();
            candidateStates += evaluation.macroEnabled().expandedStates();
            baselineCandidates += evaluation.baseline().generatedCandidates();
            candidateCandidates +=
                evaluation.macroEnabled().generatedCandidates();
            baselinePathSteps += evaluation.baseline().ruleIds().size();
            candidatePathSteps += evaluation.macroEnabled().ruleIds().size();
        }

        private ObjectNode resourceUse(ObjectNode budgets) {
            int configuredStates = integer(
                budgets, "maxStatesPerCampaign", "benchmark budgets");
            int configuredCandidates = integer(
                budgets, "maxCandidateEvaluations", "benchmark budgets");
            int configuredProofs = integer(
                budgets, "maxProofAttempts", "benchmark budgets");
            int executedStates = formationStates + baselineStates
                + candidateStates;
            int executedCandidates = formationCandidateEvaluations
                + baselineCandidates + candidateCandidates;
            require(executedStates <= configuredStates,
                "exact-one evaluation exceeds frozen state budget");
            require(executedCandidates <= configuredCandidates,
                "exact-one evaluation exceeds candidate-evaluation budget");

            ObjectNode result = JSON.createObjectNode();
            result.put("configuredStates", configuredStates);
            result.put("executedStates", executedStates);
            result.put("remainingStates", configuredStates - executedStates);
            result.put("configuredCandidateEvaluations",
                configuredCandidates);
            result.put("executedCandidateEvaluations", executedCandidates);
            result.put("remainingCandidateEvaluations",
                configuredCandidates - executedCandidates);
            result.put("configuredProofAttempts", configuredProofs);
            result.put("executedProofAttempts", 0);
            result.put("remainingProofAttempts", configuredProofs);
            result.put("formationStates", formationStates);
            result.put("formationCandidateEvaluations",
                formationCandidateEvaluations);
            result.put("baselineExpandedStates", baselineStates);
            result.put("candidateExpandedStates", candidateStates);
            result.put("expandedStateSaving",
                baselineStates - candidateStates);
            result.put("baselineGeneratedCandidates", baselineCandidates);
            result.put("candidateGeneratedCandidates", candidateCandidates);
            result.put("generatedCandidateSaving",
                baselineCandidates - candidateCandidates);
            result.put("baselinePathSteps", baselinePathSteps);
            result.put("candidatePathSteps", candidatePathSteps);
            result.put("pathStepSaving",
                baselinePathSteps - candidatePathSteps);
            addContentHash(result);
            return result;
        }
    }

    private record Arguments(
        Path benchmarkSource,
        Path corpus,
        Path profile,
        Path freezeReceipt,
        Path downstreamStream,
        Path output,
        String repositoryRevision
    ) {
        private static final Set<String> EXPECTED = Set.of(
            "--benchmark-source",
            "--corpus",
            "--profile",
            "--freeze-receipt",
            "--downstream-stream",
            "--output",
            "--repository-revision");

        private static Arguments parse(String[] args) {
            if (args.length % 2 != 0) {
                throw new IllegalArgumentException(
                    "expected --key value arguments");
            }
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index += 2) {
                String key = args[index];
                if (!key.startsWith("--")) {
                    throw new IllegalArgumentException(
                        "expected --key value arguments");
                }
                if (values.put(key, args[index + 1]) != null) {
                    throw new IllegalArgumentException(
                        "duplicate argument " + key);
                }
            }
            if (!values.keySet().equals(EXPECTED)) {
                throw new IllegalArgumentException(
                    "argument allowlist drift: " + values.keySet());
            }
            return new Arguments(
                Path.of(required(values, "--benchmark-source")),
                Path.of(required(values, "--corpus")),
                Path.of(required(values, "--profile")),
                Path.of(required(values, "--freeze-receipt")),
                Path.of(required(values, "--downstream-stream")),
                Path.of(required(values, "--output")),
                required(values, "--repository-revision"));
        }

        private static String required(
            Map<String, String> values,
            String key
        ) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                    "missing required argument " + key);
            }
            return value;
        }
    }
}
