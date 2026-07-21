package de.regelsuche.release;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence;
import de.regelsuche.discovery.domain.DomainDiscoveryRunner;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain.LinearRecurrenceCandidate;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain.LinearRecurrenceCertificate;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain.Rational;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain.RecurrenceModel;
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
import java.util.Optional;
import java.util.TreeSet;

/**
 * Executes the post-freeze linear-recurrence adapter against the immutable
 * candidate-independent finite-sequence corpus.
 *
 * <p>The frozen profile is retained unchanged with
 * {@code LINEAR_RECURRENCE=ADAPTER_REQUIRED}. This runner records that the
 * adapter became available after the freeze and binds every execution to the
 * original corpus, profile and freeze receipt. Formation reads only the two
 * TRAIN {@code formationInput} payloads; VALIDATION and TEST holdouts are read
 * only by the evaluation stage.</p>
 */
public final class CandidateIndependentLinearRecurrenceAdapterMain {
    public static final String SCHEMA =
        "regelsuche.candidate-independent-linear-recurrence-adapter-run/v1";
    private static final String CHALLENGE = "finite-difference-recurrences";
    private static final String CANDIDATE_FORM = "LINEAR_RECURRENCE";
    private static final String PROFILE_ID = "finite-sequence-candidate-forms/v1";
    private static final String FROZEN_STATUS = "ADAPTER_REQUIRED";
    private static final String RUNTIME_STATUS = "AVAILABLE_AFTER_FREEZE";
    private static final String ADAPTER_STATUS =
        "POST_FREEZE_ADAPTER_EXECUTION_WITH_FROZEN_PROFILE_RETAINED";
    private static final int CAMPAIGN_COUNT = 4;

    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private CandidateIndependentLinearRecurrenceAdapterMain() {
    }

    public static void main(String[] args) throws IOException {
        Arguments arguments = Arguments.parse(args);
        ObjectNode corpus = readObject(arguments.corpus());
        ObjectNode profile = readObject(arguments.profile());
        ObjectNode receipt = readObject(arguments.freezeReceipt());
        validateFrozenInputs(corpus, profile, receipt);

        List<ObjectNode> cases = finiteCases(corpus);
        List<ObjectNode> trainCases = cases.stream()
            .filter(item -> "TRAIN".equals(text(item, "split")))
            .toList();
        require(trainCases.size() == 2,
            "linear-recurrence adapter requires exactly two frozen TRAIN cases");

        ArrayNode campaigns = JSON.createArrayNode();
        int confirmed = 0;
        int refuted = 0;
        int inconclusive = 0;
        for (int index = 1; index <= CAMPAIGN_COUNT; index++) {
            ObjectNode campaign = executeCampaign(corpus, cases, trainCases, index);
            campaigns.add(campaign);
            for (JsonNode evaluation : requireArray(
                    campaign, "evaluations", "campaign evaluations")) {
                switch (text(evaluation, "outcome")) {
                    case "CONFIRMED_LINEAR_RECURRENCE_FIT" -> confirmed++;
                    case "REFUTED_LINEAR_RECURRENCE_FIT" -> refuted++;
                    case "NO_UNIQUE_LINEAR_RECURRENCE" -> inconclusive++;
                    default -> throw new IllegalStateException(
                        "unexpected recurrence evaluation outcome: "
                            + text(evaluation, "outcome"));
                }
            }
        }

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
        run.put("adapterStatus", ADAPTER_STATUS);
        run.put("candidateForm", CANDIDATE_FORM);
        run.put("candidateFormImplementationClass",
            LinearRecurrenceSequenceDomain.class.getName());
        run.put("frozenImplementationStatus", FROZEN_STATUS);
        run.put("runtimeImplementationStatus", RUNTIME_STATUS);
        run.put("frozenProfileModified", false);
        run.put("configuredCampaigns", CAMPAIGN_COUNT);
        run.put("executedCampaigns", CAMPAIGN_COUNT);
        run.put("configuredEvaluations", CAMPAIGN_COUNT * cases.size());
        run.put("executedEvaluations", CAMPAIGN_COUNT * cases.size());
        run.put("confirmedLinearRecurrenceEvaluations", confirmed);
        run.put("refutedLinearRecurrenceEvaluations", refuted);
        run.put("inconclusiveLinearRecurrenceEvaluations", inconclusive);
        run.put("uniqueInfiniteContinuationClaimAuthorized", false);
        run.put("formalProofStatus", "NOT_EVALUATED");
        run.put("externalNoveltyStatus", "NOT_EVALUATED");
        run.put("publicationAuthorized", false);
        run.set("campaigns", campaigns);
        addContentHash(run);

        Path output = arguments.output().toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writeValueAsString(run) + "\n",
            StandardCharsets.UTF_8);
        System.out.println("candidateIndependentLinearRecurrenceAdapter=" + output);
        System.out.println("contentHash=" + text(run, "contentHash"));
        System.out.println("confirmedEvaluations=" + confirmed);
        System.out.println("refutedEvaluations=" + refuted);
        System.out.println("inconclusiveEvaluations=" + inconclusive);
    }

    private static ObjectNode executeCampaign(
        ObjectNode corpus,
        List<ObjectNode> cases,
        List<ObjectNode> trainCases,
        int index
    ) {
        String campaignId = CHALLENGE + "-campaign-" + String.format("%02d", index);
        String configuredSeed = semanticHash(object(Map.of(
            "benchmarkId", text(corpus, "benchmarkId"),
            "campaignId", campaignId,
            "challengeId", CHALLENGE,
            "index", index)));

        ArrayNode formationEvidence = JSON.createArrayNode();
        boolean selected = false;
        for (ObjectNode trainCase : trainCases) {
            ObjectNode formation = requireObjectField(
                trainCase,
                "formationInput",
                "TRAIN case " + text(trainCase, "caseId") + " formationInput");
            require(!formation.has("holdoutContinuation"),
                "formationInput exposes a frozen holdout");
            require(!formation.path("holdoutVisible").asBoolean(true),
                "formationInput unexpectedly exposes a holdout");
            ObjectNode evidence = executeFormation(campaignId, trainCase, formation);
            formationEvidence.add(evidence);
            selected |= "SELECTED".equals(text(evidence, "candidateFormStatus"));
        }
        require(selected,
            "no TRAIN case selected the linear-recurrence candidate form");

        ArrayNode evaluations = JSON.createArrayNode();
        for (ObjectNode benchmarkCase : cases) {
            evaluations.add(executeEvaluation(campaignId, benchmarkCase));
        }

        ObjectNode campaign = JSON.createObjectNode();
        campaign.put("campaignId", campaignId);
        campaign.put("challengeId", CHALLENGE);
        campaign.put("configuredSeed", configuredSeed);
        campaign.put("status", ADAPTER_STATUS);
        campaign.put("candidateForm", CANDIDATE_FORM);
        campaign.put("candidateFormImplementationClass",
            LinearRecurrenceSequenceDomain.class.getName());
        campaign.put("frozenImplementationStatus", FROZEN_STATUS);
        campaign.put("runtimeImplementationStatus", RUNTIME_STATUS);
        campaign.put("formationVisibility", "TRAIN_ONLY");
        campaign.put("heldOutInputAccess", "EVALUATION_ONLY");
        campaign.put("publicationEligible", false);
        campaign.set("formationCaseIds", strings(trainCases.stream()
            .map(item -> text(item, "caseId"))
            .toList()));
        campaign.set("formationEvidence", formationEvidence);
        campaign.set("evaluations", evaluations);
        addContentHash(campaign);
        return campaign;
    }

    private static ObjectNode executeFormation(
        String campaignId,
        ObjectNode benchmarkCase,
        ObjectNode formation
    ) {
        String caseId = text(benchmarkCase, "caseId");
        List<Long> observed = numbers(requireArray(
            formation, "observedPrefix", "case " + caseId + " formation observedPrefix"));
        int maximumOrder = integer(
            formation, "maximumOrder", "case " + caseId + " formation maximumOrder");
        Optional<RecurrenceModel> visibleModel =
            LinearRecurrenceSequenceDomain.inferUniqueRecurrence(observed, maximumOrder);
        SyntheticHoldout synthetic = syntheticHoldout(observed, visibleModel);
        DomainDiscoveryRunner.RunResult<LinearRecurrenceCandidate,
            LinearRecurrenceCertificate> run = runProductionDomain(
                campaignId + "-formation-" + caseId,
                caseId + "-formation-seed",
                observed,
                List.of(synthetic.value()),
                maximumOrder,
                "candidate-independent-frozen-formation/" + caseId);

        ObjectNode result = JSON.createObjectNode();
        result.put("caseId", caseId);
        result.put("caseContentHash", text(benchmarkCase, "contentHash"));
        result.put("inputSurface", "formationInput");
        result.put("evaluationInputRead", false);
        result.put("holdoutVisible", false);
        result.put("syntheticHoldoutSource", synthetic.source());
        result.put("syntheticHoldout", synthetic.value());
        result.put("maximumOrder", maximumOrder);
        result.put("formedModelStatus",
            visibleModel.isPresent() ? "UNIQUE_MODEL" : "NO_UNIQUE_MODEL");
        result.put("recurrenceOrder", visibleModel.map(RecurrenceModel::order).orElse(0));
        result.set("coefficients", rationalStrings(
            visibleModel.map(RecurrenceModel::coefficients).orElse(List.of())));
        result.put("productionOutcome", run.evidence().outcome().name());
        result.put("productionEvidenceContentHash", run.evidence().contentHash());
        result.put("candidateFormStatus",
            run.evidence().outcome() == DomainDiscoveryEvidence.Outcome.CONFIRMED
                ? "SELECTED" : "NO_UNIQUE_LINEAR_RECURRENCE");
        result.set("resourceUse", resourceUse(run.evidence()));
        result.put("formalProofStatus", "NOT_EVALUATED");
        result.put("externalNoveltyStatus", "NOT_EVALUATED");
        addContentHash(result);
        return result;
    }

    private static ObjectNode executeEvaluation(
        String campaignId,
        ObjectNode benchmarkCase
    ) {
        String caseId = text(benchmarkCase, "caseId");
        ObjectNode evaluationInput = requireObjectField(
            benchmarkCase,
            "evaluationInput",
            "case " + caseId + " evaluationInput");
        List<Long> observed = numbers(requireArray(
            evaluationInput, "observedPrefix", "case " + caseId + " observedPrefix"));
        List<Long> holdout = numbers(requireArray(
            evaluationInput, "holdoutContinuation", "case " + caseId + " holdout"));
        int maximumOrder = integer(
            evaluationInput, "maximumOrder", "case " + caseId + " maximumOrder");
        Optional<RecurrenceModel> visibleModel =
            LinearRecurrenceSequenceDomain.inferUniqueRecurrence(observed, maximumOrder);
        List<Rational> predicted = visibleModel
            .map(model -> predictContinuation(model, observed, holdout.size()))
            .orElse(List.of());
        DomainDiscoveryRunner.RunResult<LinearRecurrenceCandidate,
            LinearRecurrenceCertificate> run = runProductionDomain(
                campaignId + "-evaluation-" + caseId,
                caseId + "-evaluation-seed",
                observed,
                holdout,
                maximumOrder,
                "candidate-independent-frozen-evaluation/" + caseId);

        DomainDiscoveryEvidence.Outcome productionOutcome = run.evidence().outcome();
        String outcome;
        String reasonCode;
        if (productionOutcome == DomainDiscoveryEvidence.Outcome.CONFIRMED) {
            outcome = "CONFIRMED_LINEAR_RECURRENCE_FIT";
            reasonCode = "LINEAR_RECURRENCE_HOLDOUT_CONFIRMED";
        } else if (productionOutcome == DomainDiscoveryEvidence.Outcome.REFUTED) {
            outcome = "REFUTED_LINEAR_RECURRENCE_FIT";
            reasonCode = "OBSERVED_PREFIX_RECURRENCE_REFUTED_BY_HOLDOUT";
        } else {
            require(productionOutcome == DomainDiscoveryEvidence.Outcome.INCONCLUSIVE,
                "unexpected production outcome for frozen recurrence case " + caseId
                    + ": " + productionOutcome);
            outcome = "NO_UNIQUE_LINEAR_RECURRENCE";
            reasonCode = "NO_UNIQUE_MODEL_WITHIN_FROZEN_ORDER_BOUND";
        }

        ObjectNode result = JSON.createObjectNode();
        result.put("caseId", caseId);
        result.put("caseContentHash", text(benchmarkCase, "contentHash"));
        result.put("split", text(benchmarkCase, "split"));
        result.put("structuralCluster", text(benchmarkCase, "structuralCluster"));
        result.put("formationVisibility",
            "TRAIN".equals(text(benchmarkCase, "split")) ? "ALLOWED" : "PROHIBITED");
        result.put("heldOutInputReadStage", "EVALUATION_ONLY");
        result.put("candidateForm", CANDIDATE_FORM);
        result.put("formedModelStatus",
            visibleModel.isPresent() ? "UNIQUE_MODEL" : "NO_UNIQUE_MODEL");
        result.put("recurrenceOrder", visibleModel.map(RecurrenceModel::order).orElse(0));
        result.set("coefficients", rationalStrings(
            visibleModel.map(RecurrenceModel::coefficients).orElse(List.of())));
        result.set("expectedHoldout", longStrings(holdout));
        result.set("predictedHoldout", rationalStrings(predicted));
        result.put("productionOutcome", productionOutcome.name());
        result.put("productionEvidenceContentHash", run.evidence().contentHash());
        result.put("outcome", outcome);
        result.put("reasonCode", reasonCode);
        result.put("uniqueInfiniteContinuationClaimAuthorized", false);
        result.put("formalProofStatus", "NOT_EVALUATED");
        result.put("externalNoveltyStatus", "NOT_EVALUATED");
        result.put("publicationEligible", false);
        result.set("resourceUse", resourceUse(run.evidence()));
        addContentHash(result);
        return result;
    }

    private static DomainDiscoveryRunner.RunResult<LinearRecurrenceCandidate,
        LinearRecurrenceCertificate> runProductionDomain(
        String campaignId,
        String seedId,
        List<Long> observed,
        List<Long> holdout,
        int maximumOrder,
        String sourceReference
    ) {
        require(maximumOrder >= 1 && maximumOrder <= 8,
            "maximumOrder is outside the frozen profile boundary");
        LinearRecurrenceSequenceDomain domain = new LinearRecurrenceSequenceDomain();
        DiscoveryBudget budget = new DiscoveryBudget(
            maximumOrder,
            Math.max(16, maximumOrder + 2),
            Math.max(16, maximumOrder + 2),
            4,
            Math.max(8, maximumOrder + 1),
            Math.max(16, observed.size()));
        DiscoverySeed seed = DiscoverySeed.create(
            seedId,
            domain.domainId(),
            "observed=" + csv(observed)
                + ";holdout=" + csv(holdout)
                + ";maximumOrder=" + maximumOrder,
            sourceReference);
        return new DomainDiscoveryRunner().run(campaignId, domain, seed, budget);
    }

    private static SyntheticHoldout syntheticHoldout(
        List<Long> observed,
        Optional<RecurrenceModel> model
    ) {
        if (model.isPresent()) {
            Rational predicted = model.orElseThrow().predictNext(observed);
            if (predicted.isInteger()) {
                return new SyntheticHoldout(
                    predicted.longValueExact(),
                    "UNIQUE_VISIBLE_PREFIX_RECURRENCE_PREDICTION");
            }
        }
        return new SyntheticHoldout(
            observed.getLast(),
            "VISIBLE_PREFIX_LAST_TERM_FALLBACK");
    }

    private static List<Rational> predictContinuation(
        RecurrenceModel model,
        List<Long> observed,
        int count
    ) {
        List<Rational> generated = new ArrayList<>();
        for (int index = 0; index < model.order(); index++) {
            generated.add(Rational.of(observed.get(index)));
        }
        int targetSize = observed.size() + count;
        while (generated.size() < targetSize) {
            int index = generated.size();
            Rational next = Rational.of(0);
            for (int coefficient = 0; coefficient < model.order(); coefficient++) {
                next = next.add(model.coefficients().get(coefficient).multiply(
                    generated.get(index - coefficient - 1)));
            }
            generated.add(next);
        }
        return List.copyOf(generated.subList(observed.size(), targetSize));
    }

    private static ObjectNode resourceUse(DomainDiscoveryEvidence evidence) {
        ObjectNode result = JSON.createObjectNode();
        result.put("exploredStates", evidence.states().size());
        result.put("generatedSuccessors", evidence.transitions().size());
        result.put("candidateAttempts", evidence.candidateAttempts().size());
        result.put("proofAttempts", 0);
        return result;
    }

    private static void validateFrozenInputs(
        ObjectNode corpus,
        ObjectNode profile,
        ObjectNode receipt
    ) {
        requireContentHash(corpus, "case corpus");
        requireContentHash(profile, "finite-sequence candidate-form profile");
        requireContentHash(receipt, "corpus-freeze receipt");
        require(Objects.equals(text(corpus, "contentHash"),
            text(receipt, "caseCorpusContentHash")),
            "case corpus is not bound by the freeze receipt");
        ObjectNode inventories = requireObjectField(
            receipt,
            "formationInventoryContentHashes",
            "freeze receipt formation inventory roots");
        require(Objects.equals(text(profile, "contentHash"),
            text(inventories, PROFILE_ID)),
            "finite-sequence profile is not bound by the freeze receipt");
        require("NOT_STARTED".equals(text(receipt, "executionStatusAtFreeze")),
            "benchmark execution was not NOT_STARTED at freeze time");
        require(integer(receipt, "executedCampaignsAtFreeze", "freeze receipt") == 0,
            "freeze receipt already contains campaigns");
        require(integer(receipt, "executedEvaluationsAtFreeze", "freeze receipt") == 0,
            "freeze receipt already contains evaluations");
        require(!receipt.path("publicationAuthorized").asBoolean(true),
            "freeze receipt unexpectedly authorizes publication");

        ObjectNode recurrence = null;
        for (JsonNode form : requireArray(profile, "forms", "candidate forms")) {
            if (form.isObject() && CANDIDATE_FORM.equals(text(form, "formId"))) {
                recurrence = (ObjectNode) form;
                break;
            }
        }
        require(recurrence != null, "frozen profile has no linear-recurrence form");
        require(FROZEN_STATUS.equals(text(recurrence, "implementationStatus")),
            "frozen linear-recurrence limitation was rewritten");
        require(!recurrence.has("implementationClass"),
            "frozen profile was post-hoc amended with an implementation class");
    }

    private static List<ObjectNode> finiteCases(ObjectNode corpus) {
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode item : requireArray(corpus, "cases", "case corpus cases")) {
            if (item.isObject() && CHALLENGE.equals(text(item, "challengeId"))) {
                ObjectNode benchmarkCase = (ObjectNode) item;
                requireContentHash(benchmarkCase,
                    "frozen benchmark case " + text(benchmarkCase, "caseId"));
                result.add(benchmarkCase);
            }
        }
        result.sort(Comparator.comparing(item -> text(item, "caseId")));
        require(result.stream().map(item -> text(item, "caseId")).toList().equals(
            List.of("case-07", "case-08", "case-09", "case-10", "case-11", "case-12")),
            "finite-sequence case identities changed");
        return List.copyOf(result);
    }

    private static ObjectNode readObject(Path path) throws IOException {
        JsonNode value = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        require(value != null && value.isObject(), "expected JSON object: " + path);
        return (ObjectNode) value;
    }

    private static void requireContentHash(ObjectNode value, String context) {
        require(value.hasNonNull("contentHash"), context + " has no contentHash");
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
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + java.util.HexFormat.of().formatHex(
                digest.digest(canonical(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String canonical(JsonNode value) {
        try {
            return JSON.writeValueAsString(canonicalNode(value));
        } catch (IOException exception) {
            throw new IllegalStateException("cannot canonicalize JSON", exception);
        }
    }

    private static JsonNode canonicalNode(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            value.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                result.set(name, canonicalNode(value.get(name)));
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            value.forEach(item -> result.add(canonicalNode(item)));
            return result;
        }
        return value.deepCopy();
    }

    private static ObjectNode object(Map<String, ?> values) {
        return JSON.valueToTree(new LinkedHashMap<>(values));
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode result = JSON.createArrayNode();
        values.forEach(result::add);
        return result;
    }

    private static ArrayNode longStrings(List<Long> values) {
        ArrayNode result = JSON.createArrayNode();
        values.stream().map(String::valueOf).forEach(result::add);
        return result;
    }

    private static ArrayNode rationalStrings(List<Rational> values) {
        ArrayNode result = JSON.createArrayNode();
        values.stream().map(Rational::canonical).forEach(result::add);
        return result;
    }

    private static List<Long> numbers(ArrayNode values) {
        List<Long> result = new ArrayList<>();
        for (JsonNode item : values) {
            require(item.isIntegralNumber(), "expected integral sequence term");
            result.add(item.longValue());
        }
        require(!result.isEmpty(), "sequence terms must not be empty");
        return List.copyOf(result);
    }

    private static String csv(List<Long> values) {
        return values.stream().map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(","));
    }

    private static ObjectNode requireObjectField(
        JsonNode value,
        String field,
        String context
    ) {
        JsonNode child = value.get(field);
        require(child != null && child.isObject(), context + " is not an object");
        return (ObjectNode) child;
    }

    private static ArrayNode requireArray(
        JsonNode value,
        String field,
        String context
    ) {
        JsonNode child = value.get(field);
        require(child != null && child.isArray(), context + " is not an array");
        return (ArrayNode) child;
    }

    private static String text(JsonNode value, String field) {
        JsonNode child = value.get(field);
        require(child != null && child.isTextual(), "missing text field " + field);
        return child.textValue();
    }

    private static int integer(JsonNode value, String field, String context) {
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

    private record SyntheticHoldout(long value, String source) {
    }

    private record Arguments(
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
                    "argument name must start with --: " + args[index]);
                require(values.put(args[index], args[index + 1]) == null,
                    "duplicate argument: " + args[index]);
            }
            return new Arguments(
                Path.of(required(values, "--corpus")),
                Path.of(required(values, "--profile")),
                Path.of(required(values, "--freeze-receipt")),
                Path.of(required(values, "--output")),
                required(values, "--repository-revision"));
        }

        private static String required(Map<String, String> values, String name) {
            String value = values.get(name);
            require(value != null && !value.isBlank(), "missing argument " + name);
            return value;
        }
    }
}
