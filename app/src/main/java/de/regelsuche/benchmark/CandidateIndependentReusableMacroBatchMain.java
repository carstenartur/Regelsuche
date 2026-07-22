package de.regelsuche.benchmark;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapter.ReplayEvidence;
import de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapter.ReplayTrace;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.EvaluationTask;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.FormationResult;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.FormationStatus;
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
import java.util.TreeSet;

/**
 * Executes four canonical campaigns for the frozen reusable-search-macros
 * challenge and retains the full paired production-search evidence.
 */
public final class CandidateIndependentReusableMacroBatchMain {
    public static final String SCHEMA =
        "regelsuche.candidate-independent-reusable-macro-batch/v1";
    private static final String CHALLENGE = "reusable-search-macros";
    private static final String PROFILE_ID = "macro-primitives/v1";
    private static final String STATUS =
        "POST_FREEZE_TARGET_FREE_MACRO_FORMATION_AND_PAIRED_UTILITY";
    private static final int CAMPAIGN_COUNT = 4;
    private static final int TASKS_PER_CAMPAIGN = 12;

    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    private CandidateIndependentReusableMacroBatchMain() {
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
        List<TaskBinding> tasks = evaluationTasks(cases);
        Map<String, List<String>> operationRules = operationRules(profile);
        CandidateIndependentReusableMacroAdapter adapter =
            new CandidateIndependentReusableMacroAdapter(operationRules);

        ArrayNode campaigns = JSON.createArrayNode();
        LinkedHashMap<String, Integer> aggregate = emptyOutcomeCounts();
        int totalExecutedStates = 0;
        int totalExecutedCandidates = 0;
        for (int index = 1; index <= CAMPAIGN_COUNT; index++) {
            ObjectNode campaign = executeCampaign(
                adapter, benchmark, traces, tasks, index);
            campaigns.add(campaign);
            ObjectNode counts = requireObjectField(
                campaign, "outcomeCounts", "campaign outcome counts");
            for (String outcome : aggregate.keySet()) {
                aggregate.merge(
                    outcome,
                    integer(counts, outcome, "outcome counts"),
                    Integer::sum);
            }
            ObjectNode resources = requireObjectField(
                campaign, "resourceUse", "campaign resources");
            totalExecutedStates += integer(
                resources, "executedStates", "campaign resources");
            totalExecutedCandidates += integer(
                resources,
                "executedCandidateEvaluations",
                "campaign resources");
        }
        require(aggregate.equals(expectedAggregate()),
            "frozen macro aggregate changed: " + aggregate);

        ObjectNode run = JSON.createObjectNode();
        run.put("schema", SCHEMA);
        run.put("benchmarkId", text(corpus, "benchmarkId"));
        run.put("challengeId", CHALLENGE);
        run.put("repositoryRevision", arguments.repositoryRevision());
        run.put("caseCorpusContentHash", text(corpus, "contentHash"));
        run.put("formationProfileId", PROFILE_ID);
        run.put("formationProfileContentHash", text(profile, "contentHash"));
        run.put("freezeReceiptContentHash", text(receipt, "contentHash"));
        run.put("combinedPreregistrationHash",
            text(receipt, "combinedPreregistrationHash"));
        run.put("adapterStatus", STATUS);
        run.put("configuredCampaigns", CAMPAIGN_COUNT);
        run.put("executedCampaigns", CAMPAIGN_COUNT);
        run.put("configuredPairedEvaluations",
            CAMPAIGN_COUNT * TASKS_PER_CAMPAIGN);
        run.put("executedPairedEvaluations",
            CAMPAIGN_COUNT * TASKS_PER_CAMPAIGN);
        run.put("macrosPerCampaign", 3);
        run.set("aggregateOutcomeCounts", object(aggregate));
        run.put("correctnessRegressionCount",
            aggregate.get(UtilityOutcome.CORRECTNESS_REGRESSION.name()));
        run.put("executedStates", totalExecutedStates);
        run.put("executedCandidateEvaluations", totalExecutedCandidates);
        run.put("formalProofStatus", "NOT_EVALUATED");
        run.put("externalNoveltyStatus", "NOT_EVALUATED");
        run.put("publicationAuthorized", false);
        run.set("campaigns", campaigns);
        addContentHash(run);

        Path output = arguments.output().toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(
            output,
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run)
                + "\n",
            StandardCharsets.UTF_8);
        System.out.println(
            "candidateIndependentReusableMacroBatch=" + output);
        System.out.println("contentHash=" + text(run, "contentHash"));
        System.out.println("aggregateOutcomeCounts=" + aggregate);
    }

    private static ObjectNode executeCampaign(
        CandidateIndependentReusableMacroAdapter adapter,
        ObjectNode benchmark,
        List<ReplayTrace> traces,
        List<TaskBinding> tasks,
        int index
    ) {
        String campaignId = CHALLENGE + "-campaign-"
            + String.format("%02d", index);
        FormationResult formation = adapter.form(traces);
        require(formation.status() == FormationStatus.SELECTED,
            "macro formation failed in " + campaignId + ": "
                + formation.detail());
        require(formation.macros().size() == 3,
            "expected exactly three TRAIN macros in " + campaignId);

        ArrayNode evaluations = JSON.createArrayNode();
        LinkedHashMap<String, Integer> counts = emptyOutcomeCounts();
        int taskStates = 0;
        int taskCandidates = 0;
        for (TaskBinding binding : tasks) {
            PairedEvaluation evaluation = adapter.evaluate(
                binding.task(), formation);
            counts.merge(evaluation.outcome().name(), 1, Integer::sum);
            taskStates += evaluation.baseline().expandedStates()
                + evaluation.macroEnabled().expandedStates();
            taskCandidates += evaluation.baseline().generatedCandidates()
                + evaluation.macroEnabled().generatedCandidates();
            evaluations.add(evaluation(binding, evaluation));
        }
        require(counts.equals(expectedPerCampaign()),
            "macro outcome frontier changed in " + campaignId + ": "
                + counts);

        int formationStates = formation.replayEvidence().stream()
            .mapToInt(ReplayEvidence::exploredStates).sum();
        int formationCandidates = formation.replayEvidence().stream()
            .mapToInt(item -> item.actualRuleIds().size()).sum();
        int executedStates = formationStates + taskStates;
        int executedCandidates = formationCandidates + taskCandidates;
        ObjectNode budgets = requireObjectField(
            benchmark, "budgets", "benchmark budgets");
        int configuredStates = integer(
            budgets, "maxStatesPerCampaign", "benchmark budgets");
        int configuredCandidates = integer(
            budgets, "maxCandidateEvaluations", "benchmark budgets");
        int configuredProofs = integer(
            budgets, "maxProofAttempts", "benchmark budgets");
        require(executedStates <= configuredStates,
            "macro campaign exceeds frozen state budget");
        require(executedCandidates <= configuredCandidates,
            "macro campaign exceeds frozen candidate-evaluation budget");

        ObjectNode campaign = JSON.createObjectNode();
        campaign.put("campaignId", campaignId);
        campaign.put("challengeId", CHALLENGE);
        campaign.put("configuredSeed", semanticHash(object(Map.of(
            "campaignId", campaignId,
            "challengeId", CHALLENGE,
            "index", index))));
        campaign.put("status", STATUS);
        campaign.put("formationVisibility", "TRAIN_ONLY");
        campaign.put("heldOutInputAccess", "EVALUATION_ONLY");
        campaign.put("formedMacroCount", formation.macros().size());
        campaign.set("formation", formation(formation));
        campaign.set("pairedEvaluations", evaluations);
        campaign.set("outcomeCounts", object(counts));

        LinkedHashMap<String, Object> resources = new LinkedHashMap<>();
        resources.put("configuredStates", configuredStates);
        resources.put("executedStates", executedStates);
        resources.put("remainingStates", configuredStates - executedStates);
        resources.put(
            "configuredCandidateEvaluations", configuredCandidates);
        resources.put("executedCandidateEvaluations", executedCandidates);
        resources.put(
            "remainingCandidateEvaluations",
            configuredCandidates - executedCandidates);
        resources.put("configuredProofAttempts", configuredProofs);
        resources.put("executedProofAttempts", 0);
        resources.put("remainingProofAttempts", configuredProofs);
        resources.put("formationStates", formationStates);
        resources.put(
            "formationCandidateEvaluations", formationCandidates);
        resources.put("pairedSearchStates", taskStates);
        resources.put(
            "pairedSearchCandidateEvaluations", taskCandidates);
        campaign.set("resourceUse", object(resources));
        campaign.put("correctnessRegression", false);
        campaign.put("formalProofStatus", "NOT_EVALUATED");
        campaign.put("externalNoveltyStatus", "NOT_EVALUATED");
        campaign.put("publicationEligible", false);
        addContentHash(campaign);
        return campaign;
    }

    private static ObjectNode formation(FormationResult formation) {
        ObjectNode result = JSON.createObjectNode();
        result.put("status", formation.status().name());
        result.put("detail", formation.detail());
        ArrayNode replay = JSON.createArrayNode();
        formation.replayEvidence().stream()
            .sorted(Comparator.comparing(ReplayEvidence::traceId))
            .map(CandidateIndependentReusableMacroBatchMain::replayEvidence)
            .forEach(replay::add);
        result.set("replayEvidence", replay);
        ArrayNode macros = JSON.createArrayNode();
        formation.macros().stream()
            .sorted(Comparator.comparing(MacroCandidate::macroId))
            .map(CandidateIndependentReusableMacroBatchMain::macro)
            .forEach(macros::add);
        result.set("macros", macros);
        addContentHash(result);
        return result;
    }

    private static ObjectNode replayEvidence(ReplayEvidence evidence) {
        ObjectNode result = JSON.createObjectNode();
        result.put("traceId", evidence.traceId());
        result.put("reproduced", evidence.reproduced());
        result.put("exploredStates", evidence.exploredStates());
        result.set("actualRuleIds", strings(evidence.actualRuleIds()));
        result.set(
            "assignedOperationIds",
            strings(evidence.assignedOperationIds()));
        result.set(
            "compressedOperationIds",
            strings(evidence.compressedOperationIds()));
        result.set("expressionPath", strings(evidence.expressionPath()));
        result.put("detail", evidence.detail());
        addContentHash(result);
        return result;
    }

    private static ObjectNode macro(MacroCandidate candidate) {
        ObjectNode result = JSON.createObjectNode();
        result.put("macroId", candidate.macroId());
        result.set(
            "operationSequence", strings(candidate.operationSequence()));
        result.set(
            "supportingTraceIds", strings(candidate.supportingTraceIds()));
        result.put("leftPattern", candidate.rule().leftPattern());
        result.put("rightPattern", candidate.rule().rightPattern());
        result.set(
            "parameterRelations",
            strings(candidate.rule().parameterRelations()));
        result.set("assumptions", strings(candidate.rule().assumptions()));
        result.put("proofStatus", candidate.rule().proofStatus().name());
        result.put(
            "knownRuleStatus", candidate.rule().knownRuleStatus().name());
        result.put(
            "supportingExamples", candidate.rule().supportingExamples());
        result.put("occurrenceCount", candidate.rule().occurrenceCount());
        result.put("confidenceScore", candidate.rule().confidenceScore());
        result.put("canonicalHash", candidate.rule().canonicalHash());
        result.put("validationEvidence", candidate.validationEvidence());
        ArrayNode steps = JSON.createArrayNode();
        candidate.atomicSteps().stream()
            .sorted(Comparator.comparingInt(TransformationStep::index))
            .map(CandidateIndependentReusableMacroBatchMain::atomicStep)
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
        result.put(
            "equivalencePreserving", step.equivalencePreserving());
        result.put("explanation", step.explanation());
        result.set("assumptions", strings(step.assumptions()));
        addContentHash(result);
        return result;
    }

    private static ObjectNode evaluation(
        TaskBinding binding,
        PairedEvaluation evaluation
    ) {
        ObjectNode result = JSON.createObjectNode();
        result.put("caseId", binding.caseId());
        result.put("split", binding.split());
        result.put("structuralCluster", binding.structuralCluster());
        result.put("caseContentHash", binding.caseContentHash());
        result.put("taskId", evaluation.taskId());
        result.put("source", binding.task().source());
        result.put("target", binding.task().target());
        result.set("assumptions", strings(binding.task().assumptions()));
        result.set("searchBudget", object(Map.of(
            "maxDepth", binding.task().maxDepth(),
            "maxExpandedStates", binding.task().maxExpandedStates())));
        result.put("outcome", evaluation.outcome().name());
        result.set("baseline", searchRun(evaluation.baseline()));
        result.set("macroEnabled", searchRun(evaluation.macroEnabled()));
        result.put(
            "correctnessRegression", evaluation.correctnessRegression());
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

    private static List<ReplayTrace> trainTraces(List<ObjectNode> cases) {
        List<ReplayTrace> result = new ArrayList<>();
        for (ObjectNode benchmarkCase : cases) {
            if (!"TRAIN".equals(text(benchmarkCase, "split"))) {
                continue;
            }
            ObjectNode formation = requireObjectField(
                benchmarkCase,
                "formationInput",
                "macro TRAIN formationInput");
            require(
                !formation.path("heldOutTargetsVisible").asBoolean(true),
                "macro TRAIN formation exposes held-out targets");
            require(
                PROFILE_ID.equals(
                    text(formation, "primitiveInventoryProfile")),
                "macro TRAIN case uses an unexpected primitive profile");
            for (JsonNode item : requireArray(
                    formation, "replayTraces", "macro replay traces")) {
                ObjectNode trace = requireObject(
                    item, "macro replay trace");
                result.add(new ReplayTrace(
                    text(trace, "traceId"),
                    text(trace, "source"),
                    text(trace, "target"),
                    stringValues(requireArray(
                        trace,
                        "primitiveSteps",
                        "trace primitive steps")),
                    stringValues(requireArray(
                        trace, "assumptions", "trace assumptions"))));
            }
        }
        result.sort(Comparator.comparing(ReplayTrace::traceId));
        require(result.size() == 4,
            "frozen macro TRAIN replay count changed: " + result.size());
        return List.copyOf(result);
    }

    private static List<TaskBinding> evaluationTasks(
        List<ObjectNode> cases
    ) {
        List<TaskBinding> result = new ArrayList<>();
        for (ObjectNode benchmarkCase : cases) {
            ObjectNode evaluation = requireObjectField(
                benchmarkCase,
                "evaluationInput",
                "macro evaluationInput");
            require(
                "IDENTICAL_INPUT_TARGET_INVENTORY_STRATEGY_AND_BUDGET"
                    .equals(text(evaluation, "comparisonPolicy")),
                "macro comparison policy changed");
            for (JsonNode item : requireArray(
                    evaluation, "tasks", "macro evaluation tasks")) {
                ObjectNode task = requireObject(
                    item, "macro evaluation task");
                ObjectNode budget = requireObjectField(
                    task, "searchBudget", "macro task budget");
                result.add(new TaskBinding(
                    text(benchmarkCase, "caseId"),
                    text(benchmarkCase, "split"),
                    text(benchmarkCase, "structuralCluster"),
                    text(benchmarkCase, "contentHash"),
                    new EvaluationTask(
                        text(task, "taskId"),
                        text(task, "source"),
                        text(task, "target"),
                        stringValues(requireArray(
                            task,
                            "assumptions",
                            "macro task assumptions")),
                        integer(
                            budget, "maxDepth", "macro task budget"),
                        integer(
                            budget,
                            "maxExpandedStates",
                            "macro task budget"))));
            }
        }
        result.sort(Comparator.comparing(item -> item.task().taskId()));
        require(result.size() == TASKS_PER_CAMPAIGN,
            "frozen macro task count changed: " + result.size());
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
        for (JsonNode item : requireArray(
                corpus, "cases", "case corpus")) {
            ObjectNode benchmarkCase = requireObject(
                item, "benchmark case");
            if (!CHALLENGE.equals(text(benchmarkCase, "challengeId"))) {
                continue;
            }
            requireContentHash(
                benchmarkCase,
                "macro case " + text(benchmarkCase, "caseId"));
            validateExposure(benchmarkCase);
            result.add(benchmarkCase);
        }
        result.sort(Comparator.comparing(item -> text(item, "caseId")));
        require(
            result.stream().map(item -> text(item, "caseId")).toList()
                .equals(List.of(
                    "case-13",
                    "case-14",
                    "case-15",
                    "case-16",
                    "case-17",
                    "case-18")),
            "macro case identities changed");
        return List.copyOf(result);
    }

    private static void validateExposure(ObjectNode benchmarkCase) {
        String caseId = text(benchmarkCase, "caseId");
        String split = text(benchmarkCase, "split");
        ObjectNode policy = requireObjectField(
            benchmarkCase, "exposurePolicy", "macro exposure policy");
        List<String> mayRead = stringValues(requireArray(
            policy,
            "candidateFormationMayRead",
            "formation readable inputs"));
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
        require(
            semanticHash(benchmark).equals(
                text(receipt, "benchmarkSourceContentHash")),
            "benchmark source is not bound by the freeze receipt");
        require(
            text(corpus, "contentHash").equals(
                text(receipt, "caseCorpusContentHash")),
            "case corpus is not bound by the freeze receipt");
        ObjectNode roots = requireObjectField(
            receipt,
            "formationInventoryContentHashes",
            "freeze receipt inventory roots");
        require(
            text(profile, "contentHash").equals(text(roots, PROFILE_ID)),
            "macro profile is not bound by the freeze receipt");
        require("NOT_STARTED".equals(text(benchmark, "executionStatus")),
            "benchmark source was modified after freeze");
        require(
            "NOT_STARTED".equals(
                text(receipt, "executionStatusAtFreeze")),
            "corpus was not frozen before execution");
        require(
            integer(
                receipt,
                "executedCampaignsAtFreeze",
                "receipt") == 0,
            "freeze receipt already contains campaigns");
        require(
            integer(
                receipt,
                "executedEvaluationsAtFreeze",
                "receipt") == 0,
            "freeze receipt already contains evaluations");
        require(
            !receipt.path("publicationAuthorized").asBoolean(true),
            "freeze receipt unexpectedly authorizes publication");
        require(
            "PROFILE_MAPPING_DOES_NOT_ESTABLISH_MACRO_UTILITY_OR_CASE_SUCCESS"
                .equals(text(profile, "claimPolicy")),
            "macro profile claim policy changed");
        ObjectNode budgets = requireObjectField(
            benchmark, "budgets", "benchmark budgets");
        require(
            integer(budgets, "campaignsPerChallenge", "budgets")
                == CAMPAIGN_COUNT,
            "macro campaign count changed");
    }

    private static LinkedHashMap<String, Integer> emptyOutcomeCounts() {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (UtilityOutcome outcome : UtilityOutcome.values()) {
            result.put(outcome.name(), 0);
        }
        return result;
    }

    private static LinkedHashMap<String, Integer> expectedPerCampaign() {
        LinkedHashMap<String, Integer> result = emptyOutcomeCounts();
        result.put(UtilityOutcome.IMPROVED.name(), 2);
        result.put(UtilityOutcome.NO_IMPROVEMENT.name(), 6);
        result.put(UtilityOutcome.NO_RESULT.name(), 4);
        return result;
    }

    private static LinkedHashMap<String, Integer> expectedAggregate() {
        LinkedHashMap<String, Integer> result = emptyOutcomeCounts();
        result.put(UtilityOutcome.IMPROVED.name(), 8);
        result.put(UtilityOutcome.NO_IMPROVEMENT.name(), 24);
        result.put(UtilityOutcome.NO_RESULT.name(), 16);
        return result;
    }

    private static ObjectNode readObject(Path path) throws IOException {
        require(
            Files.isRegularFile(path) && !Files.isSymbolicLink(path),
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
            throw new IllegalStateException(
                "SHA-256 unavailable", exception);
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
            require(
                value.isTextual() && !value.asText().isBlank(),
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
        require(
            child != null
                && child.isTextual()
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
        String caseId,
        String split,
        String structuralCluster,
        String caseContentHash,
        EvaluationTask task
    ) {
    }

    private record Arguments(
        Path benchmarkSource,
        Path corpus,
        Path profile,
        Path freezeReceipt,
        Path output,
        String repositoryRevision
    ) {
        private static Arguments parse(String[] args) {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index += 2) {
                if (index + 1 >= args.length
                        || !args[index].startsWith("--")) {
                    throw new IllegalArgumentException(
                        "expected --key value arguments");
                }
                values.put(args[index], args[index + 1]);
            }
            return new Arguments(
                Path.of(required(values, "--benchmark-source")),
                Path.of(required(values, "--corpus")),
                Path.of(required(values, "--profile")),
                Path.of(required(values, "--freeze-receipt")),
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
