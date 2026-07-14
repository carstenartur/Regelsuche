package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.CrossFamilyStructuralClusterer.BridgeCluster;
import de.regelsuche.mining.CrossFamilyStructuralClusterer.ClusterStatus;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Forms one target-free bridge hypothesis from an already eligible structural cluster.
 *
 * <p>The builder accepts formation conjectures only. Held-out families, validation
 * examples and outcomes are deliberately absent from this API.</p>
 */
public final class CrossFamilyBridgeHypothesisBuilder {
    public static final String SCHEMA = "regelsuche.cross-family-bridge-hypothesis/v1";

    private final CrossFamilyStructuralClusterer clusterer =
        new CrossFamilyStructuralClusterer();

    public BridgeHypothesis form(
        BridgeCluster cluster,
        List<OpenTargetConjecture> formationConjectures
    ) {
        validateCluster(cluster);
        List<OpenTargetConjecture> ordered = orderedConjectures(formationConjectures);
        validateFormationMembership(cluster, ordered);

        BridgeCluster recomputed = recomputeCluster(cluster, ordered);
        validateRecomputedCluster(cluster, recomputed);

        List<ConvergenceEvidence> evidence = ordered.stream()
            .flatMap(conjecture -> conjecture.evidence().stream())
            .sorted(Comparator.comparing(ConvergenceEvidence::observationId))
            .toList();
        TreeSet<String> observationIds = evidence.stream()
            .map(ConvergenceEvidence::observationId)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (observationIds.size() != evidence.size()) {
            throw new IllegalArgumentException(
                "formation conjectures reuse an observation ID");
        }
        int distinctAlphaSupport = (int) evidence.stream()
            .map(ConvergenceEvidence::alphaPairFingerprint)
            .distinct()
            .count();
        if (distinctAlphaSupport < 2) {
            throw new IllegalArgumentException(
                "bridge hypothesis requires alpha-distinct formation evidence");
        }

        OpenTargetConjecture representative = ordered.getFirst();
        List<String> families = List.copyOf(new TreeSet<>(cluster.postHocFamilies()));
        List<String> conjectureIds = ordered.stream()
            .map(OpenTargetConjecture::conjectureId)
            .toList();
        List<String> assumptions = cluster.assumptions().stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted()
            .toList();
        List<String> parameterRelations = representative.parameterRelations().stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted()
            .toList();
        Map<String, List<String>> placeholderValues = orderedPlaceholderValues(
            representative.expressionPlaceholderValues());

        String hypothesisId = "bridge-hypothesis-" + sha256(
            cluster.clusterId()
                + "\n" + representative.leftPattern()
                + "->" + representative.rightPattern()
                + "\n" + String.join("\u0001", families)
                + "\n" + String.join("\u0001", conjectureIds))
            .substring(0, 24);
        OpenTargetConjecture conjecture = new OpenTargetConjecture(
            hypothesisId,
            representative.leftPattern(),
            representative.rightPattern(),
            evidence.size(),
            distinctAlphaSupport,
            families,
            List.copyOf(observationIds),
            evidence,
            parameterRelations,
            placeholderValues,
            "OBSERVED_CONJECTURE",
            "EQUIVALENCE_PRESERVING_CONVERGENT_PATHS");

        String formationHash = hash(formationMaterial(
            cluster,
            hypothesisId,
            representative.leftPattern(),
            representative.rightPattern(),
            families,
            conjectureIds,
            List.copyOf(observationIds),
            assumptions,
            parameterRelations,
            placeholderValues,
            evidence));
        return new BridgeHypothesis(
            SCHEMA,
            hypothesisId,
            cluster.clusterId(),
            cluster.structuralSignatureHash(),
            false,
            representative.leftPattern(),
            representative.rightPattern(),
            families,
            conjectureIds,
            List.copyOf(observationIds),
            assumptions,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            formationHash,
            conjecture);
    }

    private static void validateCluster(BridgeCluster cluster) {
        Objects.requireNonNull(cluster, "cluster");
        if (cluster.status() != ClusterStatus.ELIGIBLE_BRIDGE) {
            throw new IllegalArgumentException(
                "bridge hypothesis requires an ELIGIBLE_BRIDGE cluster");
        }
        if (cluster.postHocFamilies().size() < 2
                || cluster.candidateIds().size() < 2) {
            throw new IllegalArgumentException(
                "bridge hypothesis requires two families and two candidates");
        }
        if (!"NOT_EVALUATED".equals(cluster.validationStatus())
                || !"NOT_EVALUATED".equals(cluster.proofStatus())
                || !"NOT_EVALUATED".equals(cluster.noveltyStatus())
                || !"NOT_EVALUATED".equals(cluster.interestingnessStatus())) {
            throw new IllegalArgumentException(
                "bridge formation must not consume downstream outcomes");
        }
    }

    private static List<OpenTargetConjecture> orderedConjectures(
        List<OpenTargetConjecture> conjectures
    ) {
        Objects.requireNonNull(conjectures, "formationConjectures");
        if (conjectures.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                "formation conjectures must not contain null");
        }
        List<OpenTargetConjecture> ordered = conjectures.stream()
            .sorted(Comparator.comparing(OpenTargetConjecture::conjectureId))
            .toList();
        if (ordered.size() < 2) {
            throw new IllegalArgumentException(
                "bridge formation requires at least two conjectures");
        }
        return ordered;
    }

    private static void validateFormationMembership(
        BridgeCluster cluster,
        List<OpenTargetConjecture> conjectures
    ) {
        TreeSet<String> expectedIds = new TreeSet<>(cluster.candidateIds());
        TreeSet<String> suppliedIds = conjectures.stream()
            .map(OpenTargetConjecture::conjectureId)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (!expectedIds.equals(suppliedIds)
                || suppliedIds.size() != conjectures.size()) {
            throw new IllegalArgumentException(
                "formation conjectures must match the source cluster exactly");
        }
        TreeSet<String> suppliedFamilies = conjectures.stream()
            .flatMap(conjecture -> conjecture.postHocFamilies().stream())
            .filter(value -> value != null && !value.isBlank())
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (!new TreeSet<>(cluster.postHocFamilies()).equals(suppliedFamilies)) {
            throw new IllegalArgumentException(
                "formation family metadata does not match the source cluster");
        }
    }

    private BridgeCluster recomputeCluster(
        BridgeCluster cluster,
        List<OpenTargetConjecture> conjectures
    ) {
        return clusterer.cluster(conjectures).clusters().stream()
            .filter(candidate -> candidate.clusterId().equals(cluster.clusterId()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "formation conjectures do not reproduce the source cluster"));
    }

    private static void validateRecomputedCluster(
        BridgeCluster expected,
        BridgeCluster actual
    ) {
        if (actual.status() != ClusterStatus.ELIGIBLE_BRIDGE
                || !expected.structuralSignatureHash().equals(
                    actual.structuralSignatureHash())
                || !expected.structuralSignature().equals(actual.structuralSignature())
                || !new TreeSet<>(expected.assumptions()).equals(
                    new TreeSet<>(actual.assumptions()))
                || !new TreeSet<>(expected.candidateIds()).equals(
                    new TreeSet<>(actual.candidateIds()))
                || !new TreeSet<>(expected.postHocFamilies()).equals(
                    new TreeSet<>(actual.postHocFamilies()))) {
            throw new IllegalArgumentException(
                "recomputed bridge cluster differs from supplied provenance");
        }
    }

    private static Map<String, List<String>> orderedPlaceholderValues(
        Map<String, List<String>> values
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> ordered = new TreeMap<>();
        values.forEach((key, entries) -> {
            if (key != null && !key.isBlank()) {
                List<String> normalized = entries == null
                    ? List.of()
                    : entries.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .distinct()
                        .sorted()
                        .toList();
                ordered.put(key, normalized);
            }
        });
        return Map.copyOf(ordered);
    }

    private static String formationMaterial(
        BridgeCluster cluster,
        String hypothesisId,
        String leftPattern,
        String rightPattern,
        List<String> families,
        List<String> conjectureIds,
        List<String> observationIds,
        List<String> assumptions,
        List<String> parameterRelations,
        Map<String, List<String>> placeholderValues,
        List<ConvergenceEvidence> evidence
    ) {
        StringBuilder material = new StringBuilder(SCHEMA)
            .append("\nhypothesis=").append(hypothesisId)
            .append("\ncluster=").append(cluster.clusterId())
            .append("\nclusterSignature=").append(cluster.structuralSignatureHash())
            .append("\ntargetProvided=false")
            .append("\nrelation=").append(leftPattern).append("->").append(rightPattern)
            .append("\nfamilies=").append(families)
            .append("\nconjectures=").append(conjectureIds)
            .append("\nobservations=").append(observationIds)
            .append("\nassumptions=").append(assumptions)
            .append("\nparameterRelations=").append(parameterRelations)
            .append("\nplaceholderValues=").append(placeholderValues);
        evidence.forEach(item -> material.append("\nevidence=")
            .append(item.observationId()).append('|')
            .append(item.alphaPairFingerprint()).append('|')
            .append(item.valuePairFingerprint()).append('|')
            .append(item.pathCompetitionSignature()));
        return material.toString();
    }

    private static String hash(String material) {
        return "sha256:" + sha256(material);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record BridgeHypothesis(
        String schema,
        String hypothesisId,
        String sourceClusterId,
        String sourceClusterSignatureHash,
        boolean targetProvided,
        String leftPattern,
        String rightPattern,
        List<String> trainingFamilies,
        List<String> sourceConjectureIds,
        List<String> supportingObservationIds,
        List<String> assumptions,
        String validationStatus,
        String proofStatus,
        String noveltyStatus,
        String ablationStatus,
        String interestingnessStatus,
        String formationHash,
        OpenTargetConjecture conjecture
    ) {
        public BridgeHypothesis {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported bridge-hypothesis schema");
            }
            if (targetProvided) {
                throw new IllegalArgumentException(
                    "cross-family bridge formation must be target-free");
            }
            requireText(hypothesisId, "hypothesisId");
            requireText(sourceClusterId, "sourceClusterId");
            requireSha256(sourceClusterSignatureHash, "sourceClusterSignatureHash");
            requireText(leftPattern, "leftPattern");
            requireText(rightPattern, "rightPattern");
            trainingFamilies = sortedDistinct(trainingFamilies);
            sourceConjectureIds = sortedDistinct(sourceConjectureIds);
            supportingObservationIds = sortedDistinct(supportingObservationIds);
            assumptions = sortedDistinct(assumptions);
            if (trainingFamilies.size() < 2 || sourceConjectureIds.size() < 2) {
                throw new IllegalArgumentException(
                    "bridge hypothesis requires two training families and conjectures");
            }
            requireNotEvaluated(validationStatus, "validationStatus");
            requireNotEvaluated(proofStatus, "proofStatus");
            requireNotEvaluated(noveltyStatus, "noveltyStatus");
            requireNotEvaluated(ablationStatus, "ablationStatus");
            requireNotEvaluated(interestingnessStatus, "interestingnessStatus");
            requireSha256(formationHash, "formationHash");
            Objects.requireNonNull(conjecture, "conjecture");
            if (!hypothesisId.equals(conjecture.conjectureId())
                    || !leftPattern.equals(conjecture.leftPattern())
                    || !rightPattern.equals(conjecture.rightPattern())
                    || !trainingFamilies.equals(
                        sortedDistinct(conjecture.postHocFamilies()))
                    || !supportingObservationIds.equals(
                        sortedDistinct(conjecture.supportingObservationIds()))) {
                throw new IllegalArgumentException(
                    "bridge hypothesis and executable conjecture differ");
            }
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("hypothesisId", hypothesisId)
                .property("sourceClusterId", sourceClusterId)
                .property("sourceClusterSignatureHash", sourceClusterSignatureHash)
                .property("targetProvided", targetProvided)
                .property("leftPattern", leftPattern)
                .property("rightPattern", rightPattern)
                .stringArray("trainingFamilies", trainingFamilies)
                .stringArray("sourceConjectureIds", sourceConjectureIds)
                .stringArray("supportingObservationIds", supportingObservationIds)
                .stringArray("assumptions", assumptions)
                .property("validationStatus", validationStatus)
                .property("proofStatus", proofStatus)
                .property("noveltyStatus", noveltyStatus)
                .property("ablationStatus", ablationStatus)
                .property("interestingnessStatus", interestingnessStatus)
                .property("formationHash", formationHash)
                .endObject()
                .toString();
        }
    }

    private static List<String> sortedDistinct(List<String> values) {
        return values == null
            ? List.of()
            : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hash");
        }
    }

    private static void requireNotEvaluated(String value, String name) {
        if (!"NOT_EVALUATED".equals(value)) {
            throw new IllegalArgumentException(
                name + " must remain NOT_EVALUATED during formation");
        }
    }
}