package de.regelsuche.release;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.CandidateForm;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.EvaluationTask;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.FormationEvidence;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.FormationResult;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.FormationSeed;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.ResourceBudget;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.ResourceUse;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.SearchResult;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.SearchStatus;
import de.regelsuche.math.algorithms.equivalence.RationalAssumptionRewriteAdapter.SearchStep;
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
 * Executes the frozen rational-assumption challenge through the production
 * target-free formation and held-out search adapter.
 */
public final class CandidateIndependentRationalAssumptionAdapterMain {
    public static final String SCHEMA =
        "regelsuche.candidate-independent-rational-assumption-adapter-run/v1";
    private static final String CHALLENGE = "rational-assumption-rewrites";
    private static final String PROFILE_ID =
        "rational-assumption-primitives/v1";
    private static final String ADAPTER_STATUS =
        "POST_FREEZE_ASSUMPTION_AWARE_CANCELLATION_EXECUTION";
    private static final String FROZEN_STATUS = "ADAPTER_REQUIRED";
    private static final String RUNTIME_STATUS = "AVAILABLE_AFTER_FREEZE";
    private static final int CAMPAIGN_COUNT = 4;
    private static final int TASKS_PER_CAMPAIGN = 12;
    private static final int FORMATION_MAX_STATES = 60;
    private static final int FORMATION_MAX_CANDIDATES = 60;
    private static final int TASK_MAX_STATES = 245;
    private static final int TASK_MAX_CANDIDATES = 45;
    private static final int SEARCH_MAX_DEPTH = 5;

    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    private CandidateIndependentRationalAssumptionAdapterMain() {
    }

    public static void main(String[] args) throws IOException {
        Arguments arguments = Arguments.parse(args);
        ObjectNode benchmark = readObject(arguments.benchmarkSource());
        ObjectNode corpus = readObject(arguments.corpus());
        ObjectNode profile = readObject(arguments.profile());
        ObjectNode receipt = readObject(arguments.freezeReceipt());
        validateFrozenInputs(benchmark, corpus, profile, receipt);

        FrozenBudget frozenBudget = frozenBudget(benchmark);
        List<ObjectNode> cases = rationalCases(corpus);
        List<ObjectNode> trainCases = cases.stream()
            .filter(item -> "TRAIN".equals(text(item, "split")))
            .toList();
        List<FormationSeed> seeds = formationSeeds(trainCases);
        require(seeds.size() == 4,
            "rational formation requires exactly four frozen TRAIN seeds");

        ArrayNode campaigns = JSON.createArrayNode();
        int reached = 0;
        int noResult = 0;
        int budgetExhausted = 0;
        int candidateNotFormed = 0;
        int targetRefuted = 0;
        for (int index = 1; index <= CAMPAIGN_COUNT; index++) {
            ObjectNode campaign = executeCampaign(
                benchmark,
                cases,
                trainCases,
                seeds,
                frozenBudget,
                index);
            campaigns.add(campaign);
            for (JsonNode evaluation : requireArray(
                    campaign, "taskEvaluations", "campaign task evaluations")) {
                switch (text(evaluation, "outcome")) {
                    case "REACHED_AND_CONFIRMED" -> reached++;
                    case "NO_RESULT" -> noResult++;
                    case "BUDGET_EXHAUSTED" -> budgetExhausted++;
                    case "CANDIDATE_NOT_FORMED" -> candidateNotFormed++;
                    case "TARGET_REFUTED" -> targetRefuted++;
                    default -> throw new IllegalStateException(
                        "unexpected rational evaluation outcome: "
                            + text(evaluation, "outcome"));
                }
            }
        }
        require(reached == 24,
            "frozen rational reached-task count changed: " + reached);
        require(noResult == 24,
            "frozen rational no-result count changed: " + noResult);
        require(budgetExhausted == 0
                && candidateNotFormed == 0
                && targetRefuted == 0,
            "unexpected terminal rational outcomes");

        ObjectNode run = JSON.createObjectNode();
        run.put("schema", SCHEMA);
        run.put("benchmarkId", text(benchmark, "benchmarkId"));
        run.put("challengeId", CHALLENGE);
        run.put("repositoryRevision", arguments.repositoryRevision());
        run.put("benchmarkSourceContentHash", text(benchmark, "contentHash"));
        run.put("caseCorpusContentHash", text(corpus, "contentHash"));
        run.put("formationProfileId", PROFILE_ID);
        run.put("formationProfileContentHash", text(profile, "contentHash"));
        run.put("freezeReceiptContentHash", text(receipt, "contentHash"));
        run.put("combinedPreregistrationHash",
            text(receipt, "combinedPreregistrationHash"));
        run.put("adapterStatus", ADAPTER_STATUS);
        run.put("candidateForm",
            RationalAssumptionRewriteAdapter.CANDIDATE_FORM_ID);
        run.put("candidateFormImplementationClass",
            RationalAssumptionRewriteAdapter.class.getName());
        run.put("frozenImplementationStatus", FROZEN_STATUS);
        run.put("runtimeImplementationStatus", RUNTIME_STATUS);
        run.put("frozenProfileModified", false);
        run.put("configuredCampaigns", CAMPAIGN_COUNT);
        run.put("executedCampaigns", CAMPAIGN_COUNT);
        run.put("configuredTaskEvaluations",
            CAMPAIGN_COUNT * TASKS_PER_CAMPAIGN);
        run.put("executedTaskEvaluations",
            CAMPAIGN_COUNT * TASKS_PER_CAMPAIGN);
        run.put("reachedAndConfirmedTaskEvaluations", reached);
        run.put("noResultTaskEvaluations", noResult);
        run.put("budgetExhaustedTaskEvaluations", budgetExhausted);
        run.put("candidateNotFormedTaskEvaluations", candidateNotFormed);
        run.put("targetRefutedTaskEvaluations", targetRefuted);
        run.put("correctnessRegressions", targetRefuted);
        run.put("configuredStatesPerCampaign", frozenBudget.maxStates());
        run.put("configuredCandidateEvaluationsPerCampaign",
            frozenBudget.maxCandidateEvaluations());
        run.put("configuredProofAttemptsPerCampaign",
            frozenBudget.maxProofAttempts());
        run.put("executedProofAttempts", 0);
        run.put("uniqueGeneralRuleClaimAuthorized", false);
        run.put("formalProofStatus", "NOT_EVALUATED");
        run.put("externalNoveltyStatus", "NOT_EVALUATED");
        run.put("publicationAuthorized", false);
        run.set("campaigns", campaigns);
        addContentHash(run);

        Path output = arguments.output().toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(
            output,
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run) + "\n",
            StandardCharsets.UTF_8);
        System.out.println("candidateIndependentRationalAssumptionAdapter="
            + output);
        System.out.println("contentHash=" + text(run, "contentHash"));
        System.out.println("reachedAndConfirmed=" + reached);
        System.out.println("noResult=" + noResult);
    }

    private static ObjectNode executeCampaign(
        ObjectNode benchmark,
        List<ObjectNode> cases,
        List<ObjectNode> trainCases,
        List<FormationSeed> seeds,
        FrozenBudget frozenBudget,
        int index
    ) {
        String campaignId = CHALLENGE + "-campaign-"
            + String.format("%02d", index);
        String configuredSeed = semanticHash(object(Map.of(
            "benchmarkId", text(benchmark, "benchmarkId"),
            "campaignId", campaignId,
            "challengeId", CHALLENGE,
            "index", index)));
        RationalAssumptionRewriteAdapter adapter =
            new RationalAssumptionRewriteAdapter();
        FormationResult formation = adapter.formCandidate(
            seeds,
            new ResourceBudget(
                SEARCH_MAX_DEPTH,
                FORMATION_MAX_STATES,
                FORMATION_MAX_CANDIDATES));
        require(formation.status()
                == RationalAssumptionRewriteAdapter.FormationStatus.SELECTED,
            "rational formation selected no candidate in " + campaignId);

        ArrayNode evaluations = JSON.createArrayNode();
        int reached = 0;
        int noResult = 0;
        int executedStates = formation.resourceUse().executedExploredStates();
        int executedCandidates =
            formation.resourceUse().executedCandidateEvaluations();
        for (ObjectNode benchmarkCase : cases) {
            ObjectNode evaluationInput = requireObjectField(
                benchmarkCase,
                "evaluationInput",
                "case " + text(benchmarkCase, "caseId")
                    + " evaluation input");
            for (JsonNode item : requireArray(
                    evaluationInput, "tasks", "rational evaluation tasks")) {
                ObjectNode task = requireObject(item, "rational evaluation task");
                SearchResult result = adapter.evaluate(
                    evaluationTask(task),
                    formation,
                    new ResourceBudget(
                        SEARCH_MAX_DEPTH,
                        TASK_MAX_STATES,
                        TASK_MAX_CANDIDATES));
                evaluations.add(renderEvaluation(
                    benchmarkCase,
                    task,
                    result));
                executedStates += result.resourceUse().executedExploredStates();
                executedCandidates +=
                    result.resourceUse().executedCandidateEvaluations();
                if (result.status() == SearchStatus.REACHED_AND_CONFIRMED) {
                    reached++;
                } else if (result.status() == SearchStatus.NO_RESULT) {
                    noResult++;
                }
            }
        }
        require(evaluations.size() == TASKS_PER_CAMPAIGN,
            "rational task matrix changed in " + campaignId);
        require(reached == 6 && noResult == 6,
            "rational campaign frontier changed in " + campaignId
                + ": reached=" + reached + " noResult=" + noResult);
        require(executedStates <= frozenBudget.maxStates(),
            "rational campaign exceeded frozen state budget");
        require(executedCandidates <= frozenBudget.maxCandidateEvaluations(),
            "rational campaign exceeded frozen candidate-evaluation budget");

        ObjectNode campaign = JSON.createObjectNode();
        campaign.put("campaignId", campaignId);
        campaign.put("challengeId", CHALLENGE);
        campaign.put("configuredSeed", configuredSeed);
        campaign.put("status", ADAPTER_STATUS);
        campaign.put("candidateForm",
            RationalAssumptionRewriteAdapter.CANDIDATE_FORM_ID);
        campaign.put("formationVisibility", "TRAIN_ONLY");
        campaign.put("heldOutTargetAccess", "EVALUATION_ONLY");
        campaign.put("publicationEligible", false);
        campaign.set("formationCaseIds", strings(trainCases.stream()
            .map(item -> text(item, "caseId"))
            .toList()));
        campaign.set("formationSeedIds", strings(seeds.stream()
            .map(FormationSeed::seedId)
            .toList()));
        campaign.set("candidate", renderCandidate(
            formation.candidate().orElseThrow()));
        campaign.set("formationEvidence", renderFormationEvidence(
            formation.evidence()));
        campaign.set("taskEvaluations", evaluations);
        ObjectNode resourceUse = campaign.putObject("resourceUse");
        resourceUse.put("configuredStates", frozenBudget.maxStates());
        resourceUse.put("executedStates", executedStates);
        resourceUse.put("remainingStates",
            frozenBudget.maxStates() - executedStates);
        resourceUse.put("configuredCandidateEvaluations",
            frozenBudget.maxCandidateEvaluations());
        resourceUse.put("executedCandidateEvaluations",
            executedCandidates);
        resourceUse.put("remainingCandidateEvaluations",
            frozenBudget.maxCandidateEvaluations() - executedCandidates);
        resourceUse.put("configuredProofAttempts",
            frozenBudget.maxProofAttempts());
        resourceUse.put("executedProofAttempts", 0);
        resourceUse.put("remainingProofAttempts",
            frozenBudget.maxProofAttempts());
        campaign.put("reachedAndConfirmedTasks", reached);
        campaign.put("noResultTasks", noResult);
        campaign.put("budgetExhaustedTasks", 0);
        campaign.put("correctnessRegressions", 0);
        campaign.put("formalProofStatus", "NOT_EVALUATED");
        campaign.put("externalNoveltyStatus", "NOT_EVALUATED");
        addContentHash(campaign);
        return campaign;
    }

    private static ObjectNode renderEvaluation(
        ObjectNode benchmarkCase,
        ObjectNode task,
        SearchResult result
    ) {
        ObjectNode output = JSON.createObjectNode();
        String split = text(benchmarkCase, "split");
        output.put("caseId", text(benchmarkCase, "caseId"));
        output.put("caseContentHash", text(benchmarkCase, "contentHash"));
        output.put("split", split);
        output.put("structuralCluster",
            text(benchmarkCase, "structuralCluster"));
        output.put("taskId", text(task, "taskId"));
        output.put("formationVisibility",
            "TRAIN".equals(split) ? "ALLOWED" : "PROHIBITED");
        output.put("targetReadStage", "EVALUATION_ONLY");
        output.put("source", text(task, "source"));
        output.put("target", text(task, "target"));
        output.set("assumptions", copyArray(requireArray(
            task, "assumptions", "task assumptions")));
        output.put("outcome", result.status().name());
        output.put("reasonCode", reasonCode(result));
        output.put("detail", result.detail());
        output.set("steps", renderSteps(result.steps()));
        output.set("resourceUse", renderResourceUse(result.resourceUse()));
        output.put("correctnessRegression",
            result.status() == SearchStatus.TARGET_REFUTED);
        output.put("formalProofStatus", "NOT_EVALUATED");
        output.put("externalNoveltyStatus", "NOT_EVALUATED");
        output.put("publicationEligible", false);
        addContentHash(output);
        return output;
    }

    private static String reasonCode(SearchResult result) {
        return switch (result.status()) {
            case REACHED_AND_CONFIRMED ->
                "TARGET_REACHED_BY_SELECTED_FORM_AND_FROZEN_PRIMITIVES";
            case NO_RESULT -> "NO_PATH_WITH_SELECTED_FORM_AND_FROZEN_PRIMITIVES";
            case BUDGET_EXHAUSTED -> "FROZEN_BUDGET_EXHAUSTED";
            case CANDIDATE_NOT_FORMED -> "FORMATION_SELECTED_NO_CANDIDATE";
            case TARGET_REFUTED -> "REACHED_TARGET_FAILED_INDEPENDENT_VALIDATION";
        };
    }

    private static ArrayNode renderSteps(List<SearchStep> steps) {
        ArrayNode result = JSON.createArrayNode();
        for (SearchStep step : steps) {
            ObjectNode item = result.addObject();
            item.put("sequence", step.sequence());
            item.put("ruleId", step.ruleId());
            item.put("source", step.source());
            item.put("target", step.target());
            item.set("generatedAssumptions", strings(
                step.generatedAssumptions()));
            item.set("requiredNonZeroFactors", strings(
                step.requiredNonZeroFactors()));
            item.set("providedNonZeroFactors", strings(
                step.providedNonZeroFactors()));
            item.put("leftCrossNormalForm", step.leftCrossNormalForm());
            item.put("rightCrossNormalForm", step.rightCrossNormalForm());
            addContentHash(item);
        }
        return result;
    }

    private static ObjectNode renderCandidate(CandidateForm candidate) {
        ObjectNode result = JSON.createObjectNode();
        result.put("candidateFormId", candidate.candidateFormId());
        result.put("operatorId", candidate.operatorId());
        result.put("implementationClass", candidate.implementationClass());
        result.set("supportSeedIds", strings(candidate.supportSeedIds()));
        result.set("frozenPrimitiveRuleIds", strings(
            candidate.frozenPrimitiveRuleIds()));
        addContentHash(result);
        return result;
    }

    private static ArrayNode renderFormationEvidence(
        List<FormationEvidence> evidence
    ) {
        ArrayNode result = JSON.createArrayNode();
        for (FormationEvidence value : evidence) {
            ObjectNode item = result.addObject();
            item.put("seedId", value.seedId());
            item.put("sourceReference", value.sourceReference());
            item.put("inputExpression", value.inputExpression());
            item.set("declaredAssumptions", strings(
                value.declaredAssumptions()));
            item.put("selectedExpression", value.selectedExpression());
            item.set("candidateAssumptions", strings(
                value.candidateAssumptions()));
            item.put("inputAstNodes", value.inputAstNodes());
            item.put("selectedAstNodes", value.selectedAstNodes());
            item.set("requiredNonZeroFactors", strings(
                value.requiredNonZeroFactors()));
            item.set("providedNonZeroFactors", strings(
                value.providedNonZeroFactors()));
            item.put("leftCrossNormalForm", value.leftCrossNormalForm());
            item.put("rightCrossNormalForm", value.rightCrossNormalForm());
            item.put("evaluationInputRead", false);
            item.put("targetVisible", false);
            addContentHash(item);
        }
        return result;
    }

    private static ObjectNode renderResourceUse(ResourceUse use) {
        ObjectNode result = JSON.createObjectNode();
        result.put("configuredStates", use.configuredExploredStates());
        result.put("executedStates", use.executedExploredStates());
        result.put("remainingStates", use.remainingExploredStates());
        result.put("configuredCandidateEvaluations",
            use.configuredCandidateEvaluations());
        result.put("executedCandidateEvaluations",
            use.executedCandidateEvaluations());
        result.put("remainingCandidateEvaluations",
            use.remainingCandidateEvaluations());
        return result;
    }

    private static EvaluationTask evaluationTask(ObjectNode task) {
        return new EvaluationTask(
            text(task, "taskId"),
            text(task, "source"),
            text(task, "target"),
            stringValues(requireArray(task, "assumptions", "task assumptions")));
    }

    private static List<FormationSeed> formationSeeds(
        List<ObjectNode> trainCases
    ) {
        List<FormationSeed> result = new ArrayList<>();
        for (ObjectNode benchmarkCase : trainCases) {
            String caseId = text(benchmarkCase, "caseId");
            ObjectNode formation = requireObjectField(
                benchmarkCase,
                "formationInput",
                "TRAIN case " + caseId + " formation input");
            require(!formation.path("targetExpressionsVisible").asBoolean(true),
                "TRAIN formation exposes target expressions");
            for (JsonNode item : requireArray(
                    formation, "seedExpressions", "formation seeds")) {
                ObjectNode seed = requireObject(item, "formation seed");
                result.add(new FormationSeed(
                    text(seed, "seedId"),
                    text(seed, "expression"),
                    stringValues(requireArray(
                        seed, "assumptions", "formation assumptions")),
                    "candidate-independent-frozen-formation/"
                        + caseId + "/" + text(seed, "seedId")));
            }
        }
        result.sort(Comparator.comparing(FormationSeed::seedId));
        return List.copyOf(result);
    }

    private static FrozenBudget frozenBudget(ObjectNode benchmark) {
        ObjectNode budgets = requireObjectField(
            benchmark, "budgets", "benchmark budgets");
        return new FrozenBudget(
            integer(budgets, "maxStatesPerCampaign", "benchmark budgets"),
            integer(budgets, "maxCandidateEvaluations", "benchmark budgets"),
            integer(budgets, "maxProofAttempts", "benchmark budgets"));
    }

    private static void validateFrozenInputs(
        ObjectNode benchmark,
        ObjectNode corpus,
        ObjectNode profile,
        ObjectNode receipt
    ) {
        requireContentHash(benchmark, "benchmark source");
        requireContentHash(corpus, "case corpus");
        requireContentHash(profile, "rational primitive profile");
        requireContentHash(receipt, "corpus-freeze receipt");
        require(text(benchmark, "contentHash").equals(
                text(receipt, "benchmarkSourceContentHash")),
            "benchmark source is not bound by the freeze receipt");
        require(text(corpus, "contentHash").equals(
                text(receipt, "caseCorpusContentHash")),
            "case corpus is not bound by the freeze receipt");
        ObjectNode inventoryRoots = requireObjectField(
            receipt,
            "formationInventoryContentHashes",
            "freeze receipt inventory roots");
        require(text(profile, "contentHash").equals(
                text(inventoryRoots, PROFILE_ID)),
            "rational profile is not bound by the freeze receipt");
        require("NOT_STARTED".equals(text(benchmark, "executionStatus")),
            "benchmark source was not NOT_STARTED at freeze time");
        require("NOT_STARTED".equals(
                text(receipt, "executionStatusAtFreeze")),
            "corpus was not frozen before evaluated execution");
        require(integer(receipt, "executedCampaignsAtFreeze", "receipt") == 0,
            "freeze receipt already contains campaigns");
        require(integer(receipt, "executedEvaluationsAtFreeze", "receipt") == 0,
            "freeze receipt already contains evaluations");
        require(!receipt.path("publicationAuthorized").asBoolean(true),
            "freeze receipt unexpectedly authorizes publication");
        require("IMPLEMENT_EXECUTION_ADAPTERS_WITHOUT_MODIFYING_FROZEN_CASE_PAYLOADS"
                .equals(text(receipt, "allowedNextStep")),
            "freeze receipt does not authorize adapter implementation");
        require(PROFILE_ID.equals(text(profile, "profileId")),
            "unexpected rational profile identity");
        require("NO_EVALUATION_TARGETS_OR_HELD_OUT_ASSUMPTION_COMBINATIONS"
                .equals(text(profile, "formationPolicy")),
            "rational formation policy changed");
        validateFrozenOperations(profile);
        FrozenBudget budget = frozenBudget(benchmark);
        require(integer(requireObjectField(benchmark, "budgets", "budgets"),
                "campaignsPerChallenge", "budgets") == CAMPAIGN_COUNT,
            "campaign count changed");
        require(FORMATION_MAX_STATES
                + TASKS_PER_CAMPAIGN * TASK_MAX_STATES
                == budget.maxStates(),
            "state-budget partition does not match frozen budget");
        require(FORMATION_MAX_CANDIDATES
                + TASKS_PER_CAMPAIGN * TASK_MAX_CANDIDATES
                == budget.maxCandidateEvaluations(),
            "candidate-budget partition does not match frozen budget");
    }

    private static void validateFrozenOperations(ObjectNode profile) {
        Map<String, JsonNode> operations = new LinkedHashMap<>();
        for (JsonNode item : requireArray(
                profile, "operations", "rational operations")) {
            operations.put(text(item, "operationId"), item);
        }
        require("AVAILABLE".equals(text(
                operations.get("DIFFERENCE_OF_SQUARES_FACTORING"),
                "implementationStatus")),
            "frozen difference-of-squares primitive is unavailable");
        for (String operationId : List.of(
                "AFFINE_FACTOR_CANCELLATION",
                "COMMON_FACTOR_CANCELLATION",
                "NESTED_DIVISION_NORMALIZATION",
                "PARTIAL_FRACTION_DECOMPOSITION")) {
            require(FROZEN_STATUS.equals(text(
                    operations.get(operationId), "implementationStatus")),
                "frozen operation status changed: " + operationId);
        }
    }

    private static List<ObjectNode> rationalCases(ObjectNode corpus) {
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode item : requireArray(corpus, "cases", "case corpus cases")) {
            if (!item.isObject() || !CHALLENGE.equals(text(item, "challengeId"))) {
                continue;
            }
            ObjectNode benchmarkCase = (ObjectNode) item;
            requireContentHash(benchmarkCase,
                "rational case " + text(benchmarkCase, "caseId"));
            validateExposure(benchmarkCase);
            result.add(benchmarkCase);
        }
        result.sort(Comparator.comparing(item -> text(item, "caseId")));
        require(result.stream().map(item -> text(item, "caseId")).toList()
                .equals(List.of(
                    "case-01", "case-02", "case-03",
                    "case-04", "case-05", "case-06")),
            "rational case identities changed");
        Map<String, Long> splits = result.stream().collect(
            java.util.stream.Collectors.groupingBy(
                item -> text(item, "split"),
                java.util.TreeMap::new,
                java.util.stream.Collectors.counting()));
        require(splits.equals(Map.of(
                "TRAIN", 2L,
                "VALIDATION", 2L,
                "TEST", 2L)),
            "rational split counts changed: " + splits);
        int tasks = result.stream()
            .mapToInt(item -> requireArray(
                requireObjectField(item, "evaluationInput", "evaluation input"),
                "tasks",
                "evaluation tasks").size())
            .sum();
        require(tasks == TASKS_PER_CAMPAIGN,
            "rational task count changed: " + tasks);
        return List.copyOf(result);
    }

    private static void validateExposure(ObjectNode benchmarkCase) {
        String caseId = text(benchmarkCase, "caseId");
        String split = text(benchmarkCase, "split");
        ObjectNode policy = requireObjectField(
            benchmarkCase, "exposurePolicy", "case exposure policy");
        ArrayNode mayRead = requireArray(
            policy, "candidateFormationMayRead", "formation readable inputs");
        ArrayNode mustNotRead = requireArray(
            policy, "candidateFormationMustNotRead", "formation prohibited inputs");
        require(mustNotRead.size() == 1
                && "evaluationInput".equals(mustNotRead.get(0).asText()),
            "case " + caseId + " does not prohibit evaluation input");
        if ("TRAIN".equals(split)) {
            require(mayRead.size() == 1
                    && "formationInput".equals(mayRead.get(0).asText()),
                "TRAIN case formation surface changed: " + caseId);
            require(benchmarkCase.path("formationInput").isObject(),
                "TRAIN case has no formation input: " + caseId);
        } else {
            require(mayRead.isEmpty(),
                "held-out case exposes formation input: " + caseId);
            require(benchmarkCase.path("formationInput").isNull(),
                "held-out case has formation payload: " + caseId);
        }
        ObjectNode evaluation = requireObjectField(
            benchmarkCase, "evaluationInput", "case evaluation input");
        require(!evaluation.path("expectedReferencesVisibleDuringFormation")
                .asBoolean(true),
            "case exposes expected references during formation: " + caseId);
    }

    private static ObjectNode readObject(Path path) throws IOException {
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path),
            "expected regular non-symbolic JSON file: " + path);
        JsonNode value = JSON.readTree(Files.readString(
            path, StandardCharsets.UTF_8));
        return requireObject(value, path.toString());
    }

    private static void requireContentHash(ObjectNode value, String context) {
        String retained = text(value, "contentHash");
        ObjectNode material = value.deepCopy();
        material.remove("contentHash");
        String expected = semanticHash(material);
        require(retained.equals(expected),
            context + " contentHash mismatch: " + retained + " != " + expected);
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
            throw new IllegalStateException("SHA-256 is unavailable", exception);
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
            throw new IllegalStateException("cannot canonicalize JSON", exception);
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

    private static ArrayNode copyArray(ArrayNode values) {
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

    private record FrozenBudget(
        int maxStates,
        int maxCandidateEvaluations,
        int maxProofAttempts
    ) {
        private FrozenBudget {
            if (maxStates < 1 || maxCandidateEvaluations < 1
                    || maxProofAttempts < 0) {
                throw new IllegalArgumentException(
                    "frozen budgets are invalid");
            }
        }
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
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index += 2) {
                require(index + 1 < args.length,
                    "arguments must use --name value pairs");
                require(args[index].startsWith("--"),
                    "argument name must start with --");
                require(values.putIfAbsent(args[index], args[index + 1]) == null,
                    "duplicate argument: " + args[index]);
            }
            TreeSet<String> expected = new TreeSet<>(List.of(
                "--benchmark-source",
                "--corpus",
                "--profile",
                "--freeze-receipt",
                "--output",
                "--repository-revision"));
            require(values.keySet().equals(expected),
                "arguments differ: expected=" + expected
                    + " actual=" + values.keySet());
            return new Arguments(
                Path.of(values.get("--benchmark-source")),
                Path.of(values.get("--corpus")),
                Path.of(values.get("--profile")),
                Path.of(values.get("--freeze-receipt")),
                Path.of(values.get("--output")),
                values.get("--repository-revision"));
        }
    }
}
