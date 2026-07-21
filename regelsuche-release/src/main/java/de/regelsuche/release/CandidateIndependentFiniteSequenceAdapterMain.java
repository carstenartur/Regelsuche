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
import de.regelsuche.discovery.domain.FiniteDifferenceSequenceDomain;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Executes the available finite-difference slice of the frozen
 * candidate-independent benchmark.
 *
 * <p>The runner consumes only TRAIN {@code formationInput} while forming the
 * available candidate form. Frozen holdouts are read only by the evaluation
 * stage. The still unavailable linear-recurrence adapter remains an explicit
 * coverage blocker, so a failed finite-difference fit is retained as
 * incomplete rather than misreported as a refutation.</p>
 */
public final class CandidateIndependentFiniteSequenceAdapterMain {
    public static final String SCHEMA =
        "regelsuche.candidate-independent-finite-sequence-adapter-run/v1";
    private static final String CHALLENGE = "finite-difference-recurrences";
    private static final String AVAILABLE_FORM = "FINITE_DIFFERENCE_POLYNOMIAL";
    private static final String MISSING_FORM = "LINEAR_RECURRENCE";
    private static final String PROFILE_ID = "finite-sequence-candidate-forms/v1";
    private static final String PARTIAL_STATUS =
        "PARTIAL_EXECUTION_WITH_INCOMPLETE_ADAPTER_COVERAGE";
    private static final int CAMPAIGN_COUNT = 4;

    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private CandidateIndependentFiniteSequenceAdapterMain() {
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
            "finite-sequence adapter requires exactly two frozen TRAIN cases");

        ArrayNode campaigns = JSON.createArrayNode();
        int confirmedEvaluations = 0;
        int incompleteEvaluations = 0;
        for (int index = 1; index <= CAMPAIGN_COUNT; index++) {
            ObjectNode campaign = executeCampaign(
                corpus, profile, cases, trainCases, index);
            campaigns.add(campaign);
            for (JsonNode evaluation : campaign.withArray("evaluations")) {
                if ("CONFIRMED_FINITE_DIFFERENCE_FIT".equals(
                        text(evaluation, "outcome"))) {
                    confirmedEvaluations++;
                } else {
                    incompleteEvaluations++;
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
        run.put("adapterStatus", PARTIAL_STATUS);
        run.put("availableCandidateForm", AVAILABLE_FORM);
        run.put("recurrenceAdapterStatus", "ADAPTER_REQUIRED");
        run.put("configuredCampaigns", CAMPAIGN_COUNT);
        run.put("executedCampaigns", CAMPAIGN_COUNT);
        run.put("configuredEvaluations", CAMPAIGN_COUNT * cases.size());
        run.put("executedEvaluations", CAMPAIGN_COUNT * cases.size());
        run.put("confirmedFiniteDifferenceEvaluations", confirmedEvaluations);
        run.put("incompleteAdapterCoverageEvaluations", incompleteEvaluations);
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
        System.out.println("candidateIndependentFiniteSequenceAdapter=" + output);
        System.out.println("contentHash=" + text(run, "contentHash"));
        System.out.println("confirmedEvaluations=" + confirmedEvaluations);
        System.out.println("incompleteEvaluations=" + incompleteEvaluations);
    }

    private static ObjectNode executeCampaign(
        ObjectNode corpus,
        ObjectNode profile,
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
        boolean availableFormSelected = false;
        for (ObjectNode trainCase : trainCases) {
            ObjectNode formation = trainCase.with("formationInput");
            require(!formation.isEmpty(),
                "TRAIN case " + text(trainCase, "caseId")
                    + " has no formationInput");
            require(!formation.has("holdoutContinuation"),
                "formationInput exposes a frozen holdout");
            require(!formation.path("holdoutVisible").asBoolean(true),
                "formationInput unexpectedly exposes a holdout");
            ObjectNode evidence = executeFormation(campaignId, trainCase, formation);
            formationEvidence.add(evidence);
            availableFormSelected |= "SELECTED".equals(
                text(evidence, "candidateFormStatus"));
        }
        require(availableFormSelected,
            "no TRAIN case selected the available finite-difference form");

        ArrayNode evaluations = JSON.createArrayNode();
        for (ObjectNode benchmarkCase : cases) {
            evaluations.add(executeEvaluation(campaignId, benchmarkCase));
        }

        ObjectNode campaign = JSON.createObjectNode();
        campaign.put("campaignId", campaignId);
        campaign.put("challengeId", CHALLENGE);
        campaign.put("configuredSeed", configuredSeed);
        campaign.put("status", PARTIAL_STATUS);
        campaign.put("candidateForm", AVAILABLE_FORM);
        campaign.put("candidateFormImplementationClass",
            availableImplementationClass(profile));
        campaign.put("formationVisibility", "TRAIN_ONLY");
        campaign.put("heldOutInputAccess", "EVALUATION_ONLY");
        campaign.put("recurrenceAdapterStatus", "ADAPTER_REQUIRED");
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
        List<Long> observed = numbers(formation.withArray("observedPrefix"));
        int maximumOrder = formation.path("maximumOrder").asInt();
        long syntheticHoldout = extrapolateFromObservedPrefix(observed, maximumOrder);
        DomainDiscoveryEvidence evidence = runProductionDomain(
            campaignId + "-formation-" + text(benchmarkCase, "caseId"),
            text(benchmarkCase, "caseId") + "-formation-seed",
            observed,
            List.of(syntheticHoldout),
            maximumOrder,
            "candidate-independent-frozen-formation/" + text(benchmarkCase, "caseId"));

        ObjectNode result = JSON.createObjectNode();
        result.put("caseId", text(benchmarkCase, "caseId"));
        result.put("caseContentHash", text(benchmarkCase, "contentHash"));
        result.put("inputSurface", "formationInput");
        result.put("evaluationInputRead", false);
        result.put("holdoutVisible", false);
        result.put("syntheticHoldoutSource",
            "DERIVED_FROM_OBSERVED_PREFIX_ONLY");
        result.put("syntheticHoldout", syntheticHoldout);
        result.put("maximumOrder", maximumOrder);
        result.put("productionOutcome", evidence.outcome().name());
        result.put("productionEvidenceContentHash", evidence.contentHash());
        result.put("candidateFormStatus",
            evidence.outcome() == DomainDiscoveryEvidence.Outcome.CONFIRMED
                ? "SELECTED" : "NO_FINITE_DIFFERENCE_CANDIDATE");
        result.put("formalProofStatus", "NOT_EVALUATED");
        result.put("externalNoveltyStatus", "NOT_EVALUATED");
        addContentHash(result);
        return result;
    }

    private static ObjectNode executeEvaluation(
        String campaignId,
        ObjectNode benchmarkCase
    ) {
        ObjectNode evaluationInput = benchmarkCase.with("evaluationInput");
        List<Long> observed = numbers(evaluationInput.withArray("observedPrefix"));
        List<Long> holdout = numbers(
            evaluationInput.withArray("holdoutContinuation"));
        int maximumOrder = evaluationInput.path("maximumOrder").asInt();
        DomainDiscoveryEvidence evidence = runProductionDomain(
            campaignId + "-evaluation-" + text(benchmarkCase, "caseId"),
            text(benchmarkCase, "caseId") + "-evaluation-seed",
            observed,
            holdout,
            maximumOrder,
            "candidate-independent-frozen-evaluation/" + text(benchmarkCase, "caseId"));

        boolean confirmed = evidence.outcome()
            == DomainDiscoveryEvidence.Outcome.CONFIRMED;
        ObjectNode result = JSON.createObjectNode();
        result.put("caseId", text(benchmarkCase, "caseId"));
        result.put("caseContentHash", text(benchmarkCase, "contentHash"));
        result.put("split", text(benchmarkCase, "split"));
        result.put("structuralCluster", text(benchmarkCase, "structuralCluster"));
        result.put("formationVisibility",
            "TRAIN".equals(text(benchmarkCase, "split"))
                ? "ALLOWED" : "PROHIBITED");
        result.put("heldOutInputReadStage", "EVALUATION_ONLY");
        result.put("candidateForm", AVAILABLE_FORM);
        result.put("productionOutcome", evidence.outcome().name());
        result.put("productionEvidenceContentHash", evidence.contentHash());
        result.put("outcome", confirmed
            ? "CONFIRMED_FINITE_DIFFERENCE_FIT"
            : "INCOMPLETE_ADAPTER_COVERAGE");
        result.put("reasonCode", confirmed
            ? "FINITE_DIFFERENCE_HOLDOUT_CONFIRMED"
            : "LINEAR_RECURRENCE_ADAPTER_REQUIRED");
        result.put("uniqueInfiniteContinuationClaimAuthorized", false);
        result.put("formalProofStatus", "NOT_EVALUATED");
        result.put("externalNoveltyStatus", "NOT_EVALUATED");
        result.put("publicationEligible", false);
        ObjectNode resourceUse = result.putObject("resourceUse");
        resourceUse.put("exploredStates", evidence.states().size());
        resourceUse.put("generatedSuccessors", evidence.transitions().size());
        resourceUse.put("candidateAttempts", evidence.candidateAttempts().size());
        resourceUse.put("proofAttempts", 0);
        addContentHash(result);
        return result;
    }

    private static DomainDiscoveryEvidence runProductionDomain(
        String campaignId,
        String seedId,
        List<Long> observed,
        List<Long> holdout,
        int maximumOrder,
        String sourceReference
    ) {
        require(maximumOrder >= 1 && maximumOrder <= 8,
            "maximumOrder is outside the frozen profile boundary");
        FiniteDifferenceSequenceDomain domain = new FiniteDifferenceSequenceDomain();
        DiscoveryBudget budget = new DiscoveryBudget(
            maximumOrder,
            Math.max(16, maximumOrder + 2),
            Math.max(16, maximumOrder + 2),
            4,
            Math.max(4, maximumOrder + 1),
            Math.max(16, observed.size()));
        DiscoverySeed seed = DiscoverySeed.create(
            seedId,
            domain.domainId(),
            "observed=" + csv(observed) + ";holdout=" + csv(holdout),
            sourceReference);
        return new DomainDiscoveryRunner().run(campaignId, domain, seed, budget)
            .evidence();
    }

    private static void validateFrozenInputs(
        ObjectNode corpus,
        ObjectNode profile,
        ObjectNode receipt
    ) {
        requireHash(corpus, "case corpus");
        requireHash(profile, "formation profile");
        requireHash(receipt, "freeze receipt");
        require(CHALLENGE.equals("finite-difference-recurrences"),
            "unexpected challenge identity");
        require(PROFILE_ID.equals(text(profile, "profileId")),
            "unexpected finite-sequence profile identity");
        require(text(corpus, "contentHash").equals(
                text(receipt, "caseCorpusContentHash")),
            "case corpus is not bound by the freeze receipt");
        require(text(profile, "contentHash").equals(
                receipt.path("formationInventoryContentHashes").path(PROFILE_ID).asText()),
            "finite-sequence profile is not bound by the freeze receipt");
        require("IMPLEMENT_EXECUTION_ADAPTERS_WITHOUT_MODIFYING_FROZEN_CASE_PAYLOADS"
                .equals(text(receipt, "allowedNextStep")),
            "freeze receipt does not authorize adapter implementation");
        require("NOT_STARTED".equals(text(receipt, "executionStatusAtFreeze")),
            "corpus was not frozen before execution");
        require(!receipt.path("publicationAuthorized").asBoolean(true),
            "freeze receipt unexpectedly authorizes publication");

        Map<String, JsonNode> forms = new LinkedHashMap<>();
        for (JsonNode form : profile.withArray("forms")) {
            forms.put(text(form, "formId"), form);
        }
        require("AVAILABLE".equals(text(forms.get(AVAILABLE_FORM),
                "implementationStatus")),
            "finite-difference form is not available in the frozen profile");
        require("ADAPTER_REQUIRED".equals(text(forms.get(MISSING_FORM),
                "implementationStatus")),
            "linear recurrence limitation disappeared from the frozen profile");
    }

    private static List<ObjectNode> finiteCases(ObjectNode corpus) {
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode item : corpus.withArray("cases")) {
            ObjectNode benchmarkCase = requireObject(item, "benchmark case");
            requireHash(benchmarkCase, "benchmark case " + text(item, "caseId"));
            if (CHALLENGE.equals(text(item, "challengeId"))) {
                result.add(benchmarkCase);
            }
        }
        result.sort(Comparator.comparing(item -> text(item, "caseId")));
        require(result.size() == 6,
            "expected exactly six frozen finite-sequence cases");
        Map<String, Long> splits = result.stream().collect(
            java.util.stream.Collectors.groupingBy(
                item -> text(item, "split"),
                java.util.TreeMap::new,
                java.util.stream.Collectors.counting()));
        require(splits.equals(Map.of("TRAIN", 2L, "VALIDATION", 2L, "TEST", 2L)),
            "finite-sequence split counts changed: " + splits);
        return List.copyOf(result);
    }

    private static String availableImplementationClass(ObjectNode profile) {
        for (JsonNode form : profile.withArray("forms")) {
            if (AVAILABLE_FORM.equals(text(form, "formId"))) {
                return text(form, "implementationClass");
            }
        }
        throw new IllegalArgumentException("available form missing from profile");
    }

    private static long extrapolateFromObservedPrefix(
        List<Long> observed,
        int maximumOrder
    ) {
        List<List<Long>> rows = new ArrayList<>();
        rows.add(new ArrayList<>(observed));
        while (rows.size() <= maximumOrder && rows.getLast().size() > 1) {
            List<Long> previous = rows.getLast();
            List<Long> next = new ArrayList<>(previous.size() - 1);
            for (int index = 0; index + 1 < previous.size(); index++) {
                next.add(Math.subtractExact(previous.get(index + 1), previous.get(index)));
            }
            rows.add(next);
        }
        for (int level = rows.size() - 2; level >= 0; level--) {
            List<Long> row = rows.get(level);
            List<Long> lower = rows.get(level + 1);
            row.add(Math.addExact(row.getLast(), lower.getLast()));
        }
        return rows.getFirst().getLast();
    }

    private static ObjectNode readObject(Path path) throws IOException {
        require(Files.isRegularFile(path), "missing regular JSON file: " + path);
        return requireObject(JSON.readTree(path.toFile()), path.toString());
    }

    private static ObjectNode requireObject(JsonNode node, String description) {
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException(description + " must be a JSON object");
        }
        return object;
    }

    private static void requireHash(ObjectNode value, String description) {
        String retained = text(value, "contentHash");
        ObjectNode unhashed = value.deepCopy();
        unhashed.remove("contentHash");
        require(retained.matches("sha256:[0-9a-f]{64}"),
            description + " has no canonical contentHash");
        require(retained.equals(semanticHash(unhashed)),
            description + " contentHash mismatch");
    }

    private static void addContentHash(ObjectNode value) {
        require(!value.has("contentHash"),
            "contentHash must be assigned exactly once");
        value.put("contentHash", semanticHash(value));
    }

    private static String semanticHash(JsonNode value) {
        return "sha256:" + hex(sha256(canonicalJson(value)
            .getBytes(StandardCharsets.UTF_8)));
    }

    private static String canonicalJson(JsonNode value) {
        try {
            if (value.isObject()) {
                List<String> fields = new TreeSet<String>() {{
                    value.fieldNames().forEachRemaining(this::add);
                }}.stream().toList();
                StringBuilder result = new StringBuilder("{");
                for (int index = 0; index < fields.size(); index++) {
                    if (index > 0) {
                        result.append(',');
                    }
                    String field = fields.get(index);
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

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }

    private static List<Long> numbers(ArrayNode array) {
        require(!array.isEmpty(), "numeric sequence must not be empty");
        List<Long> result = new ArrayList<>();
        for (JsonNode item : array) {
            require(item.isIntegralNumber(), "sequence term must be an integer");
            result.add(item.longValue());
        }
        return List.copyOf(result);
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode result = JSON.createArrayNode();
        values.forEach(result::add);
        return result;
    }

    private static ObjectNode object(Map<String, ?> values) {
        return JSON.valueToTree(values);
    }

    private static String csv(List<Long> values) {
        return values.stream().map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(","));
    }

    private static String text(JsonNode node, String field) {
        Objects.requireNonNull(node, "node");
        JsonNode value = node.get(field);
        require(value != null && value.isTextual() && !value.asText().isBlank(),
            "missing textual field " + field);
        return value.asText();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
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
                    "arguments must be supplied as --name value pairs");
                require(args[index].startsWith("--"),
                    "unexpected argument " + args[index]);
                require(values.putIfAbsent(args[index], args[index + 1]) == null,
                    "duplicate argument " + args[index]);
            }
            TreeSet<String> expected = new TreeSet<>(List.of(
                "--corpus", "--profile", "--freeze-receipt", "--output",
                "--repository-revision"));
            require(values.keySet().equals(expected),
                "arguments differ: expected=" + expected
                    + " actual=" + values.keySet());
            return new Arguments(
                Path.of(values.get("--corpus")),
                Path.of(values.get("--profile")),
                Path.of(values.get("--freeze-receipt")),
                Path.of(values.get("--output")),
                values.get("--repository-revision"));
        }
    }
}
