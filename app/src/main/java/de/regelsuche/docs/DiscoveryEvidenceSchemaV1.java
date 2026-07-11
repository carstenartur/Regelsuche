package de.regelsuche.docs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.search.SearchSpaceAnalytics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Stable v1 envelope for mathematical discovery evidence. */
final class DiscoveryEvidenceSchemaV1 {
    static final String SCHEMA_ID = "regelsuche.discovery-evidence/v1";
    static final String PRODUCER_ID = "regelsuche.discovery-gallery";

    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final List<String> PROFILE_ORDER = List.of("observed", "validated", "promoted", "public");

    EvidenceDocument createDocument(
        DiscoveryBenchmarkScenario scenario,
        DiscoveryBenchmarkEvidence evidence,
        PublicBenchmarkEvidenceGate.GateDecision gateDecision,
        List<ArtifactDescriptor> artifacts
    ) {
        Map<String, Object> document = baseDocument(scenario, evidence, gateDecision, artifacts);
        String canonicalHash = canonicalEvidenceHash(document);
        String canonicalId = canonicalEvidenceId(canonicalHash);
        document.put("canonicalEvidenceHash", canonicalHash);
        document.put("canonicalEvidenceId", canonicalId);
        return new EvidenceDocument(canonicalId, canonicalHash, Map.copyOf(document));
    }

    String prettyJson(Map<String, Object> document) throws JsonProcessingException {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document);
    }

    JsonNode read(Path path) throws IOException {
        return JSON.readTree(path.toFile());
    }

    void assertValidDocument(Map<String, Object> document, Path evidenceDirectory) {
        JsonNode root = JSON.valueToTree(document);
        List<String> errors = validate(root, evidenceDirectory);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid discovery evidence v1:\n - " + String.join("\n - ", errors));
        }
    }

    void assertValidDocument(JsonNode document, Path evidenceDirectory) {
        List<String> errors = validate(document, evidenceDirectory);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid discovery evidence v1:\n - " + String.join("\n - ", errors));
        }
    }

    String recomputeCanonicalEvidenceHash(JsonNode document, Path evidenceDirectory) {
        ObjectNode mutable = document.deepCopy();
        mutable.remove("canonicalEvidenceHash");
        mutable.remove("canonicalEvidenceId");
        JsonNode artifactsNode = mutable.path("artifacts");
        if (evidenceDirectory != null && artifactsNode.isArray()) {
            for (JsonNode artifactNode : artifactsNode) {
                if (!(artifactNode instanceof ObjectNode objectNode)) {
                    continue;
                }
                String relativePath = objectNode.path("path").asText("");
                Path artifactPath = resolveArtifactPath(evidenceDirectory, relativePath);
                if (!Files.isRegularFile(artifactPath)) {
                    throw new IllegalStateException("artifact file missing: " + relativePath);
                }
                objectNode.put("sha256", "sha256:" + sha256(readBytes(artifactPath)));
            }
        }
        try {
            return "sha256:" + sha256(JSON.writeValueAsBytes(JSON.convertValue(mutable, Object.class)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not canonicalize discovery evidence", exception);
        }
    }

    private Map<String, Object> baseDocument(
        DiscoveryBenchmarkScenario scenario,
        DiscoveryBenchmarkEvidence evidence,
        PublicBenchmarkEvidenceGate.GateDecision gateDecision,
        List<ArtifactDescriptor> artifacts
    ) {
        LinkedHashMap<String, Object> document = new LinkedHashMap<>();
        document.put("schemaId", SCHEMA_ID);
        document.put("profiles", profilesFor(gateDecision));
        document.put("generatedBy", PRODUCER_ID);
        document.put("scenarioId", evidence.scenarioId());
        document.put("inputExpression", evidence.inputExpression());
        document.put("targetExpression", evidence.targetExpression());
        document.put("nodeCount", evidence.nodeCount());
        document.put("edgeCount", evidence.edgeCount());
        document.put("bridgeRulesUsed", evidence.bridgeRulesUsed());
        document.put("learnedMacros", evidence.learnedMacros());
        document.put("reusedMacros", evidence.reusedMacros());
        document.put("sourceRef", "generated");
        document.put("generatorVersion", "1");
        document.put("scenarioVersion", "1");
        document.put("evidenceSchemaVersion", "1");
        document.put("producer", producer());
        document.put("subject", subject(scenario, evidence));
        document.put("claims", claims(scenario, evidence));
        document.put("observations", observations(evidence));
        document.put("generalizedHypothesis", generalizedHypothesis(scenario, evidence));
        document.put("revisions", List.of());
        document.put("assumptions", aggregateAssumptions(evidence));
        document.put("counterexamples", List.of());
        document.put("holdouts", holdouts(evidence));
        document.put("oracleResults", oracleResults(evidence));
        document.put("provenance", provenance(scenario));
        document.put("novelty", novelty());
        document.put("ablation", ablation(evidence));
        document.put("proof", proof());
        document.put("promotion", promotion(evidence, gateDecision));
        document.put("artifacts", artifacts.stream().map(ArtifactDescriptor::toMap).toList());
        document.put("extensions", Map.of());
        JSON.convertValue(evidence, new TypeReference<LinkedHashMap<String, Object>>() { })
            .forEach(document::putIfAbsent);
        return document;
    }

    private Map<String, Object> producer() {
        LinkedHashMap<String, Object> producer = new LinkedHashMap<>();
        producer.put("producerId", PRODUCER_ID);
        producer.put("producerVersion", "1");
        producer.put("implementation", "regelsuche");
        producer.put("sourceRef", "generated");
        producer.put("schemaPath", "docs/schemas/regelsuche.discovery-evidence-v1.schema.json");
        producer.put("extensions", Map.of());
        return producer;
    }

    private Map<String, Object> subject(DiscoveryBenchmarkScenario scenario, DiscoveryBenchmarkEvidence evidence) {
        LinkedHashMap<String, Object> subject = new LinkedHashMap<>();
        subject.put("id", scenario.id());
        subject.put("kind", "DISCOVERY_SCENARIO");
        subject.put("title", scenario.displayName());
        subject.put("inputExpression", evidence.inputExpression());
        subject.put("targetExpression", evidence.targetExpression());
        subject.put("extensions", Map.of());
        return subject;
    }

    private List<Map<String, Object>> claims(DiscoveryBenchmarkScenario scenario, DiscoveryBenchmarkEvidence evidence) {
        List<Map<String, Object>> claims = new ArrayList<>();

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", "claim-generated-summary");
        summary.put("kind", "GENERATED_SUMMARY");
        summary.put("outcome", evidence.success() ? "SUPPORTED" : "INCONCLUSIVE");
        summary.put("statement", scenario.displayName() + " reached the target expression through generated search evidence.");
        summary.put("evidenceRefs", List.of("observation-search-trace", "oracle-equivalence", "ablation-macro-reuse"));
        summary.put("extensions", Map.of());
        claims.add(summary);

        LinkedHashMap<String, Object> hypothesis = new LinkedHashMap<>();
        hypothesis.put("id", "claim-generalized-hypothesis");
        hypothesis.put("kind", "GENERALIZED_HYPOTHESIS");
        hypothesis.put("outcome", evidence.success() ? "SUPPORTED" : "INCONCLUSIVE");
        hypothesis.put("statement", generalizedStatement(scenario, evidence));
        hypothesis.put("evidenceRefs", List.of("observation-search-trace"));
        hypothesis.put("extensions", Map.of());
        claims.add(hypothesis);
        return claims;
    }

    private List<Map<String, Object>> observations(DiscoveryBenchmarkEvidence evidence) {
        LinkedHashMap<String, Object> observation = new LinkedHashMap<>();
        observation.put("id", "observation-search-trace");
        observation.put("kind", "SEARCH_TRACE");
        observation.put("outcome", evidence.success() ? "OBSERVED" : "INCONCLUSIVE");
        observation.put("pathCount", evidence.foundPaths().size());
        observation.put("nodeCount", evidence.nodeCount());
        observation.put("edgeCount", evidence.edgeCount());
        observation.put("bridgeRulesUsed", evidence.bridgeRulesUsed());
        observation.put("learnedMacros", evidence.learnedMacros());
        observation.put("reusedMacros", evidence.reusedMacros());
        observation.put("validationStatus", blankToDefault(evidence.validationStatus(), "UNKNOWN"));
        observation.put("sourceFields", List.of("foundPaths", "nodes", "edges", "analytics", "withoutMacroRun", "withMacroRun"));
        observation.put("extensions", Map.of());
        return List.of(observation);
    }

    private Map<String, Object> generalizedHypothesis(DiscoveryBenchmarkScenario scenario, DiscoveryBenchmarkEvidence evidence) {
        LinkedHashMap<String, Object> hypothesis = new LinkedHashMap<>();
        hypothesis.put("statement", generalizedStatement(scenario, evidence));
        hypothesis.put("family", scenario.requiredRuleFamilies().isEmpty() ? "discovery" : scenario.requiredRuleFamilies().getFirst());
        hypothesis.put("rulePath", evidence.bridgeRulesUsed());
        hypothesis.put("status", evidence.success() ? "SUPPORTED" : "INCONCLUSIVE");
        hypothesis.put("extensions", Map.of());
        return hypothesis;
    }

    private String generalizedStatement(DiscoveryBenchmarkScenario scenario, DiscoveryBenchmarkEvidence evidence) {
        String family = scenario.requiredRuleFamilies().isEmpty() ? "discovery" : scenario.requiredRuleFamilies().getFirst();
        return family + " transforms `" + evidence.inputExpression() + "` into `" + evidence.targetExpression() + "`.";
    }

    private List<String> aggregateAssumptions(DiscoveryBenchmarkEvidence evidence) {
        LinkedHashSet<String> assumptions = new LinkedHashSet<>();
        for (DiscoveryBenchmarkEvidence.EvidenceEdge edge : evidence.edges()) {
            assumptions.addAll(edge.assumptions());
            if (edge.rewriteMove() != null) {
                assumptions.addAll(edge.rewriteMove().assumptions());
            }
        }
        return List.copyOf(assumptions);
    }

    private List<Map<String, Object>> holdouts(DiscoveryBenchmarkEvidence evidence) {
        List<Map<String, Object>> holdouts = new ArrayList<>();
        if (evidence.withoutMacroRun() != null) {
            holdouts.add(holdout("without-macro", evidence.withoutMacroRun()));
        }
        if (evidence.withMacroRun() != null) {
            holdouts.add(holdout("with-macro", evidence.withMacroRun()));
        }
        return holdouts;
    }

    private Map<String, Object> holdout(String id, DiscoveryBenchmarkEvidence.SearchRunEvidence run) {
        LinkedHashMap<String, Object> holdout = new LinkedHashMap<>();
        holdout.put("id", id);
        holdout.put("kind", "REPLAY_RUN");
        holdout.put("success", run.success());
        holdout.put("pathLength", run.path().isEmpty() ? -1 : Math.max(0, run.path().size() - 1));
        holdout.put("statesExplored", run.analytics() == null ? -1L : run.analytics().statesExplored());
        holdout.put("extensions", Map.of());
        return holdout;
    }

    private List<Map<String, Object>> oracleResults(DiscoveryBenchmarkEvidence evidence) {
        LinkedHashMap<String, Object> oracle = new LinkedHashMap<>();
        oracle.put("id", "oracle-equivalence");
        oracle.put("kind", "EXTERNAL_VALIDATION");
        oracle.put("status", blankToDefault(evidence.oracleStatus(), "UNAVAILABLE"));
        oracle.put("outcome", oracleOutcome(evidence.oracleStatus()));
        oracle.put("evidence", blankToDefault(evidence.oracleEvidence(), ""));
        oracle.put("extensions", Map.of());
        return List.of(oracle);
    }

    private String oracleOutcome(String status) {
        String normalized = blankToDefault(status, "UNAVAILABLE").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "AGREE", "PASS", "CONFIRMED" -> "CONFIRMED";
            case "DISAGREE", "FAIL", "REFUTED" -> "REFUTED";
            default -> "INCONCLUSIVE";
        };
    }

    private Map<String, Object> provenance(DiscoveryBenchmarkScenario scenario) {
        LinkedHashMap<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("traceSource", "REGELSUCHE_SEARCH");
        provenance.put("enabledOperators", scenario.enabledOperators());
        provenance.put("enabledRulePacks", scenario.enabledRulePacks());
        provenance.put("requiredBridgeRules", scenario.requiredBridgeRules());
        provenance.put("requiredRuleFamilies", scenario.requiredRuleFamilies());
        provenance.put("requiredBridgeEffects", scenario.requiredBridgeEffects().stream().map(Enum::name).toList());
        provenance.put("budgets", Map.of(
            "maxDepth", scenario.budgets().maxDepth(),
            "maxStates", scenario.budgets().maxStates(),
            "timeoutMillis", scenario.budgets().timeoutMillis()
        ));
        provenance.put("gallery", Map.of(
            "generateSvg", scenario.gallery().generateSvg(),
            "preferredPathCount", scenario.gallery().preferredPathCount(),
            "minVisibleNodes", scenario.gallery().minVisibleNodes()
        ));
        provenance.put("extensions", Map.of());
        return provenance;
    }

    private Map<String, Object> novelty() {
        LinkedHashMap<String, Object> novelty = new LinkedHashMap<>();
        novelty.put("status", "DOCUMENTED_BENCHMARK");
        novelty.put("matchedEvidenceIds", List.of());
        novelty.put("rationale", "Public discovery benchmark scenario.");
        novelty.put("extensions", Map.of());
        return novelty;
    }

    private Map<String, Object> ablation(DiscoveryBenchmarkEvidence evidence) {
        AblationEvidence ablation = ablationEvidence(evidence);
        LinkedHashMap<String, Object> object = new LinkedHashMap<>();
        object.put("kind", "ABLATION_STUDY");
        object.put("status", ablation.ablationStatus());
        object.put("outcome", ablation.promotionReady() ? "CONFIRMED" : "INCONCLUSIVE");
        object.put("improvementRatio", ablation.improvementRatio());
        object.put("withCandidate", runEvidence(ablation.withCandidate()));
        object.put("withoutCandidate", runEvidence(ablation.withoutCandidate()));
        object.put("explanation", ablation.explanation());
        object.put("extensions", Map.of());
        return object;
    }

    private Map<String, Object> runEvidence(AblationEvidence.RunEvidence evidence) {
        LinkedHashMap<String, Object> run = new LinkedHashMap<>();
        run.put("success", evidence.success());
        run.put("pathLength", evidence.pathLength());
        run.put("statesExplored", evidence.statesExplored());
        return run;
    }

    private Map<String, Object> proof() {
        LinkedHashMap<String, Object> proof = new LinkedHashMap<>();
        proof.put("required", false);
        proof.put("policy", "OPTIONAL");
        proof.put("outcome", "NOT_REQUESTED");
        proof.put("confirmations", List.of());
        proof.put("extensions", Map.of());
        return proof;
    }

    private Map<String, Object> promotion(
        DiscoveryBenchmarkEvidence evidence,
        PublicBenchmarkEvidenceGate.GateDecision gateDecision
    ) {
        LinkedHashMap<String, Object> promotion = new LinkedHashMap<>();
        promotion.put("status", gateDecision.accepted() ? "PUBLIC" : "PROMOTED");
        promotion.put("eligible", evidence.promotionEligible());
        promotion.put("gateAccepted", gateDecision.accepted());
        promotion.put("gateId", "public-benchmark-evidence-gate");
        promotion.put("rejectionReasons", gateDecision.rejectionReasons());
        promotion.put("extensions", Map.of());
        return promotion;
    }

    private List<String> profilesFor(PublicBenchmarkEvidenceGate.GateDecision gateDecision) {
        if (gateDecision.accepted()) {
            return PROFILE_ORDER;
        }
        return PROFILE_ORDER.subList(0, 3);
    }

    private String canonicalEvidenceHash(Map<String, Object> document) {
        try {
            return "sha256:" + sha256(JSON.writeValueAsBytes(document));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize discovery evidence", exception);
        }
    }

    private String canonicalEvidenceId(String canonicalHash) {
        return SCHEMA_ID + "#" + canonicalHash;
    }

    private AblationEvidence ablationEvidence(DiscoveryBenchmarkEvidence evidence) {
        DiscoveryBenchmarkEvidence.SearchRunEvidence withMacro = evidence.withMacroRun();
        DiscoveryBenchmarkEvidence.SearchRunEvidence withoutMacro = evidence.withoutMacroRun();
        if (withMacro == null || withoutMacro == null) {
            return AblationEvidence.statusOnly("N/A", "benchmark evidence does not contain both runs");
        }
        return AblationEvidence.compare(
            withMacro.success(),
            pathLength(withMacro),
            statesExplored(withMacro),
            withoutMacro.success(),
            pathLength(withoutMacro),
            statesExplored(withoutMacro),
            "public benchmark macro reuse ablation"
        );
    }

    private int pathLength(DiscoveryBenchmarkEvidence.SearchRunEvidence run) {
        return run.path().isEmpty() ? -1 : Math.max(0, run.path().size() - 1);
    }

    private long statesExplored(DiscoveryBenchmarkEvidence.SearchRunEvidence run) {
        SearchSpaceAnalytics analytics = run.analytics();
        return analytics == null ? -1L : analytics.statesExplored();
    }

    private List<String> validate(JsonNode root, Path evidenceDirectory) {
        List<String> errors = new ArrayList<>();
        requireText(root, "schemaId", errors);
        if (!SCHEMA_ID.equals(root.path("schemaId").asText())) {
            errors.add("schemaId must be " + SCHEMA_ID);
        }

        Set<String> profiles = profiles(root, errors);
        requireObject(root, "producer", errors);
        requireObject(root, "subject", errors);
        requireArray(root, "claims", errors);
        requireArray(root, "observations", errors);
        requireObject(root, "generalizedHypothesis", errors);
        requireArray(root, "revisions", errors);
        requireArray(root, "assumptions", errors);
        requireArray(root, "counterexamples", errors);
        requireArray(root, "holdouts", errors);
        requireArray(root, "oracleResults", errors);
        requireObject(root, "provenance", errors);
        requireObject(root, "novelty", errors);
        requireObject(root, "ablation", errors);
        requireObject(root, "proof", errors);
        requireObject(root, "promotion", errors);
        requireArray(root, "artifacts", errors);
        requireObject(root, "extensions", errors);
        requireText(root.path("producer"), "producerId", errors);
        requireText(root.path("subject"), "id", errors);
        requireText(root.path("subject"), "inputExpression", errors);
        requireText(root.path("subject"), "targetExpression", errors);
        requireText(root.path("generalizedHypothesis"), "statement", errors);
        requireText(root.path("generalizedHypothesis"), "family", errors);

        if (profiles.contains("validated")) {
            if (root.path("provenance").isMissingNode() || root.path("provenance").isEmpty()) {
                errors.add("validated profile requires provenance");
            }
            if (!root.has("assumptions") || !root.path("assumptions").isArray()) {
                errors.add("validated profile requires assumptions");
            }
            if (!root.path("oracleResults").isArray() || root.path("oracleResults").isEmpty()) {
                errors.add("validated profile requires oracleResults");
            }
        }
        if (profiles.contains("promoted")) {
            if (root.path("ablation").isMissingNode() || blank(root.path("ablation").path("status"))) {
                errors.add("promoted profile requires ablation");
            }
        }
        if (profiles.contains("public")) {
            requireText(root, "canonicalEvidenceHash", errors);
            requireText(root, "canonicalEvidenceId", errors);
            if (!root.path("artifacts").isArray() || root.path("artifacts").isEmpty()) {
                errors.add("public profile requires artifacts");
            }
            if (!"PUBLIC".equals(root.path("promotion").path("status").asText())) {
                errors.add("public profile requires promotion.status=PUBLIC");
            }
            JsonNode proof = root.path("proof");
            if (proof.path("required").asBoolean(false)) {
                if (!"CONFIRMED".equals(proof.path("outcome").asText())) {
                    errors.add("public profile requires confirmed proof when proof.required=true");
                }
                if (!proof.path("confirmations").isArray() || proof.path("confirmations").isEmpty()) {
                    errors.add("public profile requires proof confirmations when proof.required=true");
                }
            }
            if (root.hasNonNull("canonicalEvidenceHash") && root.hasNonNull("canonicalEvidenceId")) {
                String expectedId = canonicalEvidenceId(root.path("canonicalEvidenceHash").asText(""));
                if (!expectedId.equals(root.path("canonicalEvidenceId").asText())) {
                    errors.add("canonicalEvidenceId must match schemaId + canonicalEvidenceHash");
                }
            }
        }

        if (evidenceDirectory != null && root.path("artifacts").isArray()) {
            for (JsonNode artifact : root.path("artifacts")) {
                if (!artifact.isObject()) {
                    errors.add("artifact entries must be objects");
                    continue;
                }
                String relativePath = artifact.path("path").asText("");
                String expected = artifact.path("sha256").asText("");
                Path artifactPath;
                try {
                    artifactPath = resolveArtifactPath(evidenceDirectory, relativePath);
                } catch (IllegalStateException exception) {
                    errors.add(exception.getMessage());
                    continue;
                }
                if (!Files.isRegularFile(artifactPath)) {
                    errors.add("artifact file missing: " + relativePath);
                    continue;
                }
                String actual = "sha256:" + sha256(readBytes(artifactPath));
                if (!expected.equals(actual)) {
                    errors.add("artifact hash mismatch for " + relativePath);
                }
            }
            if (root.hasNonNull("canonicalEvidenceHash")) {
                try {
                    String recomputed = recomputeCanonicalEvidenceHash(root, evidenceDirectory);
                    if (!recomputed.equals(root.path("canonicalEvidenceHash").asText())) {
                        errors.add("canonicalEvidenceHash does not match canonical artifact state");
                    }
                } catch (IllegalStateException exception) {
                    errors.add(exception.getMessage());
                }
            }
        }
        return errors;
    }

    private Set<String> profiles(JsonNode root, List<String> errors) {
        JsonNode profilesNode = root.path("profiles");
        LinkedHashSet<String> profiles = new LinkedHashSet<>();
        if (!profilesNode.isArray() || profilesNode.isEmpty()) {
            errors.add("profiles must be a non-empty array");
            return profiles;
        }
        for (JsonNode profile : profilesNode) {
            String value = profile.asText();
            if (!PROFILE_ORDER.contains(value)) {
                errors.add("unknown profile: " + value);
            }
            if (!profiles.add(value)) {
                errors.add("duplicate profile: " + value);
            }
        }
        List<String> canonicalOrder = PROFILE_ORDER.stream().filter(profiles::contains).toList();
        if (!canonicalOrder.equals(List.copyOf(profiles))) {
            errors.add("profiles must use canonical order " + canonicalOrder);
        }
        return profiles;
    }

    private Path resolveArtifactPath(Path evidenceDirectory, String relativePath) {
        Path normalizedEvidenceDirectory = evidenceDirectory.toAbsolutePath().normalize();
        Path artifactPath = normalizedEvidenceDirectory.resolve(relativePath).normalize();
        if (!artifactPath.startsWith(normalizedEvidenceDirectory)) {
            throw new IllegalStateException("artifact path escapes evidence directory: " + relativePath);
        }
        return artifactPath;
    }

    private void requireText(JsonNode node, String field, List<String> errors) {
        if (blank(node.path(field))) {
            errors.add(field + " is required");
        }
    }

    private void requireObject(JsonNode node, String field, List<String> errors) {
        if (!node.path(field).isObject()) {
            errors.add(field + " must be an object");
        }
    }

    private void requireArray(JsonNode node, String field, List<String> errors) {
        if (!node.path(field).isArray()) {
            errors.add(field + " must be an array");
        }
    }

    private boolean blank(JsonNode node) {
        return node.isMissingNode() || node.asText("").isBlank();
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return bytesToHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read artifact " + path, exception);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    record ArtifactDescriptor(String name, String path, String mediaType, String sha256) {
        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("name", name);
            artifact.put("path", path);
            artifact.put("mediaType", mediaType);
            artifact.put("sha256", sha256);
            return artifact;
        }
    }

    record EvidenceDocument(String canonicalEvidenceId, String canonicalEvidenceHash, Map<String, Object> body) {
    }
}
