package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenCandidateIndependentBenchmarkContractTest {
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
    );
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private static final String EXPECTED_SCHEMA =
        "regelsuche.candidate-independent-benchmark-source/v1";
    private static final String EXPECTED_BENCHMARK_ID =
        "regelsuche-candidate-independent-autonomous-discovery-2026-07/v1";
    private static final String EXPECTED_PORTFOLIO =
        "regelsuche-evaluator-backed-challenges-2026-07/v1";
    private static final String EXPECTED_PORTFOLIO_HASH =
        "sha256:b1b8caa2eacab13ad859506ce1a6c409a97262cf868c9ed6a5f5ad89b1ccb2e9";
    private static final String EXPECTED_CLAIM_POLICY =
        "BENCHMARK_SUCCESS_DOES_NOT_IMPLY_EXTERNAL_MATHEMATICAL_NOVELTY";

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
        "schema",
        "benchmarkId",
        "portfolioId",
        "portfolioContentHash",
        "claimPolicy",
        "publicationAuthorized",
        "executionStatus",
        "formationVisibility",
        "budgets",
        "metrics",
        "cases"
    );
    private static final Set<String> EXPECTED_SPLITS = Set.of(
        "TRAIN", "VALIDATION", "TEST"
    );
    private static final Set<String> EXPECTED_CHALLENGES = Set.of(
        "rational-assumption-rewrites",
        "finite-difference-recurrences",
        "reusable-search-macros"
    );
    private static final List<String> EXPECTED_OUTCOMES = List.of(
        "ACCEPTED",
        "REJECTED",
        "DISPROVED",
        "NO_RESULT",
        "TIMEOUT",
        "UNSUPPORTED",
        "INCOMPLETE"
    );
    private static final Map<String, Integer> EXPECTED_BUDGETS = Map.of(
        "campaignsPerChallenge", 4,
        "maxCandidateEvaluations", 600,
        "maxProofAttempts", 100,
        "maxStatesPerCampaign", 3000
    );
    private static final List<String> EXPECTED_METRICS = List.of(
        "configuredCampaigns",
        "executedCampaigns",
        "zeroOutputCampaigns",
        "acceptedCandidates",
        "rejectedCandidates",
        "disprovedCandidates",
        "heldOutReachability",
        "exploredStateDelta",
        "correctnessRegressions",
        "resourceUsage",
        "structuralSupportDiversity"
    );
    private static final List<String> EXPECTED_PROHIBITED_FIELDS = List.of(
        "target",
        "expectedAnswer",
        "hiddenReference",
        "testLabel",
        "postHocFamilyAnnotation"
    );
    private static final Set<String> EXPECTED_CASE_FIELDS = Set.of(
        "caseId",
        "challengeId",
        "structuralCluster",
        "split",
        "formationVisible",
        "expectedAnswerVisible",
        "targetVisibleDuringFormation",
        "outcomePolicy"
    );
    private static final Map<String, Integer> EXPECTED_SPLIT_COUNTS = Map.of(
        "TRAIN", 6,
        "VALIDATION", 6,
        "TEST", 6
    );
    private static final Map<String, Integer> EXPECTED_PER_CHALLENGE = Map.of(
        "TRAIN", 2,
        "VALIDATION", 2,
        "TEST", 2
    );

    @Test
    void repositoryPreregistrationSatisfiesTheFrozenContract()
            throws IOException {
        VerificationResult result = verify(sourcePath());

        assertEquals(EXPECTED_BENCHMARK_ID, result.benchmarkId());
        assertEquals(EXPECTED_SPLIT_COUNTS, result.splitCounts());
        assertTrue(
            result.canonicalSourceHash().matches("sha256:[0-9a-f]{64}"),
            result.canonicalSourceHash()
        );

        System.out.println("verifiedBenchmarkId=" + result.benchmarkId());
        System.out.println(
            "canonicalSourceHash=" + result.canonicalSourceHash()
        );
        System.out.println("splitCounts=TRAIN:6,VALIDATION:6,TEST:6");
        System.out.println("splitUnitIsolation=VERIFIED");
        System.out.println("executionStatus=NOT_STARTED");
    }

    @Test
    void strictParserRejectsDuplicateKeysAndTrailingValues(
        @TempDir Path temporary
    ) throws IOException {
        Path duplicate = temporary.resolve("duplicate.json");
        Files.writeString(
            duplicate,
            "{\"schema\":\"first\",\"schema\":\"second\"}",
            StandardCharsets.UTF_8
        );
        IllegalArgumentException duplicateFailure = assertThrows(
            IllegalArgumentException.class,
            () -> verify(duplicate)
        );
        assertTrue(
            duplicateFailure.getMessage().toLowerCase().contains("duplicate"),
            duplicateFailure.getMessage()
        );

        Path trailing = temporary.resolve("trailing.json");
        Files.writeString(
            trailing,
            "{\"schema\":\"first\"} {}",
            StandardCharsets.UTF_8
        );
        IllegalArgumentException trailingFailure = assertThrows(
            IllegalArgumentException.class,
            () -> verify(trailing)
        );
        assertTrue(
            trailingFailure.getMessage().contains("trailing JSON content"),
            trailingFailure.getMessage()
        );
    }

    @Test
    void contractRejectsUnknownFieldsAndPublicationDrift(
        @TempDir Path temporary
    ) throws IOException {
        ObjectNode unexpected = repositoryDocument();
        unexpected.put("postHocResult", "forbidden");
        IllegalArgumentException unknownFailure = assertThrows(
            IllegalArgumentException.class,
            () -> verify(writeFixture(
                temporary.resolve("unknown.json"),
                unexpected
            ))
        );
        assertTrue(
            unknownFailure.getMessage().contains("top-level unknown="),
            unknownFailure.getMessage()
        );

        ObjectNode publication = repositoryDocument();
        publication.put("publicationAuthorized", true);
        IllegalArgumentException publicationFailure = assertThrows(
            IllegalArgumentException.class,
            () -> verify(writeFixture(
                temporary.resolve("publication.json"),
                publication
            ))
        );
        assertTrue(
            publicationFailure.getMessage().contains(
                "publication must remain unauthorized"
            ),
            publicationFailure.getMessage()
        );
    }

    @Test
    void contractRejectsFormationLeakAndCrossSplitClusterReuse(
        @TempDir Path temporary
    ) throws IOException {
        ObjectNode visibility = repositoryDocument();
        caseAt(visibility, 2).put("formationVisible", true);
        IllegalArgumentException visibilityFailure = assertThrows(
            IllegalArgumentException.class,
            () -> verify(writeFixture(
                temporary.resolve("visibility.json"),
                visibility
            ))
        );
        assertTrue(
            visibilityFailure.getMessage().contains(
                "formation visibility leak in case-03"
            ),
            visibilityFailure.getMessage()
        );

        ObjectNode cluster = repositoryDocument();
        caseAt(cluster, 2).put(
            "structuralCluster",
            caseAt(cluster, 0).get("structuralCluster").asText()
        );
        IllegalArgumentException clusterFailure = assertThrows(
            IllegalArgumentException.class,
            () -> verify(writeFixture(
                temporary.resolve("cluster.json"),
                cluster
            ))
        );
        assertTrue(
            clusterFailure.getMessage().contains("crosses splits"),
            clusterFailure.getMessage()
        );
    }

    @Test
    void contractRejectsOutcomeOrderDrift(@TempDir Path temporary)
            throws IOException {
        ObjectNode document = repositoryDocument();
        ArrayNode policy = (ArrayNode) caseAt(document, 0).get(
            "outcomePolicy"
        );
        JsonNode first = policy.remove(0);
        policy.add(first);

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> verify(writeFixture(
                temporary.resolve("outcome-order.json"),
                document
            ))
        );
        assertTrue(
            failure.getMessage().contains("outcome policy drift in case-01"),
            failure.getMessage()
        );
    }

    private static VerificationResult verify(Path path) throws IOException {
        ObjectNode document = requireObject(parseStrict(path), "top-level");
        requireExactFields(document, TOP_LEVEL_FIELDS, "top-level");

        requireEquals(
            EXPECTED_SCHEMA,
            requiredText(document, "schema", "top-level"),
            "unexpected schema"
        );
        requireEquals(
            EXPECTED_BENCHMARK_ID,
            requiredText(document, "benchmarkId", "top-level"),
            "benchmark identity drift"
        );
        requireEquals(
            EXPECTED_PORTFOLIO,
            requiredText(document, "portfolioId", "top-level"),
            "portfolio identity drift"
        );
        requireEquals(
            EXPECTED_PORTFOLIO_HASH,
            requiredText(document, "portfolioContentHash", "top-level"),
            "portfolio content hash drift"
        );
        requireEquals(
            EXPECTED_CLAIM_POLICY,
            requiredText(document, "claimPolicy", "top-level"),
            "claim policy drift"
        );
        requireEquals(
            "NOT_STARTED",
            requiredText(document, "executionStatus", "top-level"),
            "evaluated execution must not start in the preregistration"
        );
        if (requiredBoolean(
                document,
                "publicationAuthorized",
                "top-level")) {
            throw invalid("publication must remain unauthorized");
        }

        verifyBudgets(requireObject(document.get("budgets"), "budgets"));
        requireEquals(
            EXPECTED_METRICS,
            requiredTextArray(document, "metrics", "top-level"),
            "metric contract drift"
        );
        verifyFormationVisibility(requireObject(
            document.get("formationVisibility"),
            "formationVisibility"
        ));

        Map<String, Integer> splitCounts = verifyCases(requireArray(
            document.get("cases"),
            "cases"
        ));
        return new VerificationResult(
            requiredText(document, "benchmarkId", "top-level"),
            canonicalHash(document),
            Map.copyOf(splitCounts)
        );
    }

    private static void verifyBudgets(ObjectNode budgets) {
        requireExactFields(budgets, EXPECTED_BUDGETS.keySet(), "budgets");
        for (Map.Entry<String, Integer> expected :
                EXPECTED_BUDGETS.entrySet()) {
            JsonNode actual = budgets.get(expected.getKey());
            if (actual == null
                    || !actual.isIntegralNumber()
                    || !actual.canConvertToInt()
                    || actual.intValue() != expected.getValue()) {
                throw invalid("budget drift");
            }
        }
    }

    private static void verifyFormationVisibility(ObjectNode visibility) {
        requireExactFields(
            visibility,
            Set.of("allowedSplits", "prohibitedFields"),
            "formationVisibility"
        );
        requireEquals(
            List.of("TRAIN"),
            requiredTextArray(
                visibility,
                "allowedSplits",
                "formationVisibility"
            ),
            "candidate formation visibility is not TRAIN-only"
        );
        requireEquals(
            EXPECTED_PROHIBITED_FIELDS,
            requiredTextArray(
                visibility,
                "prohibitedFields",
                "formationVisibility"
            ),
            "prohibited formation fields drift"
        );
    }

    private static Map<String, Integer> verifyCases(ArrayNode cases) {
        if (cases.size() != 18) {
            throw invalid("expected 18 cases, found " + cases.size());
        }

        Set<String> ids = new HashSet<>();
        Map<String, Integer> splitCounts = new TreeMap<>();
        Set<String> challenges = new TreeSet<>();
        Map<String, Map<String, Integer>> challengeSplitCounts =
            new TreeMap<>();
        Map<ClusterKey, String> clusterOwners = new HashMap<>();

        for (int index = 0; index < cases.size(); index++) {
            ObjectNode candidate = requireObject(
                cases.get(index),
                "case index " + index
            );
            String provisionalId = candidate.path("caseId").isTextual()
                ? candidate.path("caseId").asText()
                : "index " + index;
            requireExactFields(
                candidate,
                EXPECTED_CASE_FIELDS,
                "case " + provisionalId
            );

            String caseId = requiredText(candidate, "caseId", "case");
            if (!ids.add(caseId)) {
                throw invalid("duplicate caseId");
            }
            String challenge = requiredText(
                candidate,
                "challengeId",
                "case " + caseId
            );
            String split = requiredText(
                candidate,
                "split",
                "case " + caseId
            );
            String cluster = requiredText(
                candidate,
                "structuralCluster",
                "case " + caseId
            );

            if (!EXPECTED_SPLITS.contains(split)) {
                throw invalid("unknown split in " + caseId);
            }
            splitCounts.merge(split, 1, Integer::sum);
            challenges.add(challenge);
            challengeSplitCounts
                .computeIfAbsent(challenge, ignored -> new TreeMap<>())
                .merge(split, 1, Integer::sum);

            boolean expectedVisibility = "TRAIN".equals(split);
            if (requiredBoolean(
                    candidate,
                    "formationVisible",
                    "case " + caseId) != expectedVisibility) {
                throw invalid("formation visibility leak in " + caseId);
            }
            if (requiredBoolean(
                    candidate,
                    "expectedAnswerVisible",
                    "case " + caseId)) {
                throw invalid("expected answer leak in " + caseId);
            }
            if (requiredBoolean(
                    candidate,
                    "targetVisibleDuringFormation",
                    "case " + caseId)) {
                throw invalid("target leak in " + caseId);
            }

            List<String> outcomePolicy = requiredTextArray(
                candidate,
                "outcomePolicy",
                "case " + caseId
            );
            if (new HashSet<>(outcomePolicy).size()
                    != outcomePolicy.size()) {
                throw invalid("duplicate outcome in " + caseId);
            }
            if (!EXPECTED_OUTCOMES.equals(outcomePolicy)) {
                throw invalid("outcome policy drift in " + caseId);
            }

            ClusterKey clusterKey = new ClusterKey(challenge, cluster);
            String previous = clusterOwners.putIfAbsent(
                clusterKey,
                split
            );
            if (previous != null && !previous.equals(split)) {
                throw invalid(
                    "structural cluster " + clusterKey
                        + " crosses splits (" + previous + ", "
                        + split + ")"
                );
            }
        }

        requireEquals(
            EXPECTED_SPLIT_COUNTS,
            splitCounts,
            "unexpected split counts: " + splitCounts
        );
        requireEquals(
            EXPECTED_CHALLENGES,
            challenges,
            "unexpected challenges: " + challenges
        );
        for (String challenge : EXPECTED_CHALLENGES) {
            Map<String, Integer> actual = challengeSplitCounts.getOrDefault(
                challenge,
                Map.of()
            );
            requireEquals(
                EXPECTED_PER_CHALLENGE,
                actual,
                "challenge " + challenge
                    + " has unexpected split counts: " + actual
            );
        }
        return Map.copyOf(splitCounts);
    }

    private static JsonNode parseStrict(Path path) throws IOException {
        try (JsonParser parser = JSON.getFactory().createParser(path.toFile())) {
            JsonNode document = JSON.readTree(parser);
            if (document == null) {
                throw invalid("empty JSON document");
            }
            if (parser.nextToken() != null) {
                throw invalid("trailing JSON content");
            }
            return document;
        } catch (JsonProcessingException exception) {
            throw invalid(
                "invalid JSON: " + exception.getOriginalMessage(),
                exception
            );
        }
    }

    private static String canonicalHash(JsonNode document) {
        try {
            Object generic = JSON.convertValue(document, Object.class);
            byte[] canonical = CANONICAL_JSON.writeValueAsBytes(generic);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                canonical
            );
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException exception) {
            throw invalid("cannot canonicalize preregistration", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireExactFields(
        ObjectNode value,
        Set<String> expected,
        String context
    ) {
        Set<String> actual = new TreeSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        Set<String> unknown = new TreeSet<>(actual);
        unknown.removeAll(expected);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        if (!unknown.isEmpty() || !missing.isEmpty()) {
            throw invalid(
                context + " unknown=" + unknown + " missing=" + missing
            );
        }
    }

    private static ObjectNode requireObject(
        JsonNode value,
        String context
    ) {
        if (!(value instanceof ObjectNode object)) {
            throw invalid(context + " must be an object");
        }
        return object;
    }

    private static ArrayNode requireArray(JsonNode value, String context) {
        if (!(value instanceof ArrayNode array)) {
            throw invalid(context + " must be an array");
        }
        return array;
    }

    private static String requiredText(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(context + " field " + field + " must be text");
        }
        return value.asText();
    }

    private static boolean requiredBoolean(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid(context + " field " + field + " must be boolean");
        }
        return value.booleanValue();
    }

    private static List<String> requiredTextArray(
        ObjectNode object,
        String field,
        String context
    ) {
        ArrayNode values = requireArray(
            object.get(field),
            context + " field " + field
        );
        List<String> result = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw invalid(
                    context + " field " + field
                        + " must contain only text"
                );
            }
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    private static <T> void requireEquals(
        T expected,
        T actual,
        String message
    ) {
        if (!expected.equals(actual)) {
            throw invalid(message);
        }
    }

    private static ObjectNode repositoryDocument() throws IOException {
        return requireObject(parseStrict(sourcePath()), "top-level")
            .deepCopy();
    }

    private static ObjectNode caseAt(ObjectNode document, int index) {
        return requireObject(
            requireArray(document.get("cases"), "cases").get(index),
            "case index " + index
        );
    }

    private static Path writeFixture(Path path, JsonNode document)
            throws IOException {
        Files.writeString(
            path,
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document),
            StandardCharsets.UTF_8
        );
        return path;
    }

    private static Path sourcePath() {
        return repositoryRoot().resolve(
            "research/benchmarks/candidate-independent/benchmark-source.json"
        );
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("regelsuche.repositoryRoot");
        assertTrue(
            configured != null && !configured.isBlank(),
            "Maven must expose maven.multiModuleProjectDirectory to tests"
        );
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(
            "candidate-independent preregistration invalid: " + message
        );
    }

    private static IllegalArgumentException invalid(
        String message,
        Throwable cause
    ) {
        return new IllegalArgumentException(
            "candidate-independent preregistration invalid: " + message,
            cause
        );
    }

    private record ClusterKey(
        String challengeId,
        String structuralCluster
    ) { }

    private record VerificationResult(
        String benchmarkId,
        String canonicalSourceHash,
        Map<String, Integer> splitCounts
    ) { }
}
