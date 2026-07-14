package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Post-hoc structural clustering that never uses family or concrete Rule IDs as features. */
public final class CrossFamilyStructuralClusterer {
    public static final String SCHEMA = "regelsuche.cross-family-structural-clusters/v1";
    private static final String GRAPH_ROLE = "EQUIVALENCE_PRESERVING_CONVERGENCE";

    private final OpenTargetConjectureNoveltyChecker signatures =
        new OpenTargetConjectureNoveltyChecker();

    public ClusterReport cluster(List<OpenTargetConjecture> conjectures) {
        Objects.requireNonNull(conjectures, "conjectures");
        List<OpenTargetConjecture> ordered = conjectures.stream()
            .sorted(Comparator.comparing(OpenTargetConjecture::conjectureId))
            .toList();
        Map<ClusterKey, List<OpenTargetConjecture>> groups = new TreeMap<>();
        List<ClusterRejection> rejections = new ArrayList<>();

        for (OpenTargetConjecture conjecture : ordered) {
            String supportProblem = supportProblem(conjecture);
            if (!supportProblem.isBlank()) {
                rejections.add(new ClusterRejection(
                    conjecture.conjectureId(), "UNSUPPORTED_CONJECTURE", supportProblem));
                continue;
            }
            var signature = signatures.check(
                conjecture, new KnownRuleRepository(), List.of());
            if (signature.status() == NoveltyStatus.INCONCLUSIVE_UNPARSEABLE
                    || signature.alphaSignatureHash().isBlank()) {
                rejections.add(new ClusterRejection(
                    conjecture.conjectureId(),
                    "UNPARSEABLE_RELATION",
                    signature.explanation()));
                continue;
            }
            ClusterKey key = new ClusterKey(
                signature.alphaSignatureHash(), assumptionSignature(conjecture), GRAPH_ROLE);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(conjecture);
        }

        List<BridgeCluster> clusters = new ArrayList<>();
        groups.forEach((key, members) -> evaluateGroup(key, members, clusters, rejections));
        clusters.sort(Comparator.comparing(BridgeCluster::clusterId));
        rejections.sort(Comparator.comparing(ClusterRejection::scopeId)
            .thenComparing(ClusterRejection::reason)
            .thenComparing(ClusterRejection::detail));
        String material = canonicalMaterial(clusters, rejections);
        return new ClusterReport(
            SCHEMA,
            clusters,
            rejections,
            hash(material));
    }

    private static void evaluateGroup(
        ClusterKey key,
        List<OpenTargetConjecture> members,
        List<BridgeCluster> clusters,
        List<ClusterRejection> rejections
    ) {
        List<OpenTargetConjecture> ordered = members.stream()
            .sorted(Comparator.comparing(OpenTargetConjecture::conjectureId))
            .toList();
        String scope = "cluster:" + key.alphaRelationHash().substring(0, 20);
        if (ordered.size() < 2) {
            rejections.add(new ClusterRejection(
                scope,
                "INSUFFICIENT_CONJECTURES",
                "one conjecture cannot establish a cross-family bridge"));
            return;
        }

        TreeSet<String> families = new TreeSet<>();
        TreeSet<String> observationIds = new TreeSet<>();
        TreeSet<String> alphaEvidence = new TreeSet<>();
        TreeSet<String> valueEvidence = new TreeSet<>();
        TreeSet<String> conjectureIds = new TreeSet<>();
        ordered.forEach(conjecture -> {
            conjectureIds.add(conjecture.conjectureId());
            conjecture.evidence().forEach(evidence -> collectEvidence(
                evidence, families, observationIds, alphaEvidence, valueEvidence));
        });

        if (families.size() < 2) {
            rejections.add(new ClusterRejection(
                scope,
                "SINGLE_FAMILY_SUPPORT",
                "post-hoc families=" + families));
            return;
        }
        if (alphaEvidence.size() < 2) {
            rejections.add(new ClusterRejection(
                scope,
                "RENAMING_ONLY_SUPPORT",
                "distinct alpha-pair fingerprints=" + alphaEvidence.size()));
            return;
        }
        if (valueEvidence.size() < 2) {
            rejections.add(new ClusterRejection(
                scope,
                "DUPLICATE_VALUE_SUPPORT",
                "distinct value-pair fingerprints=" + valueEvidence.size()));
            return;
        }

        OpenTargetConjecture representative = ordered.getFirst();
        String clusterId = "bridge-" + hash(
            key.alphaRelationHash() + "\n" + key.assumptionSignature() + "\n" + key.graphRole())
            .substring("sha256:".length(), "sha256:".length() + 24);
        clusters.add(new BridgeCluster(
            clusterId,
            key.alphaRelationHash(),
            representative.leftPattern(),
            representative.rightPattern(),
            key.graphRole(),
            splitAssumptions(key.assumptionSignature()),
            List.copyOf(families),
            List.copyOf(conjectureIds),
            List.copyOf(observationIds),
            alphaEvidence.size(),
            valueEvidence.size(),
            "OBSERVED_CROSS_FAMILY_BRIDGE",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED"));
    }

    private static void collectEvidence(
        ConvergenceEvidence evidence,
        TreeSet<String> families,
        TreeSet<String> observationIds,
        TreeSet<String> alphaEvidence,
        TreeSet<String> valueEvidence
    ) {
        if (evidence.family() != null && !evidence.family().isBlank()) {
            families.add(evidence.family());
        }
        if (evidence.observationId() != null && !evidence.observationId().isBlank()) {
            observationIds.add(evidence.observationId());
        }
        if (evidence.alphaPairFingerprint() != null
                && !evidence.alphaPairFingerprint().isBlank()) {
            alphaEvidence.add(evidence.alphaPairFingerprint());
        }
        if (evidence.valuePairFingerprint() != null
                && !evidence.valuePairFingerprint().isBlank()) {
            valueEvidence.add(evidence.valuePairFingerprint());
        }
    }

    private static String supportProblem(OpenTargetConjecture conjecture) {
        if (conjecture == null) {
            return "conjecture must not be null";
        }
        if (!"OBSERVED_CONJECTURE".equals(conjecture.candidateStatus())) {
            return "candidateStatus=" + conjecture.candidateStatus();
        }
        if (!"EQUIVALENCE_PRESERVING_CONVERGENT_PATHS".equals(
                conjecture.evidenceStatus())) {
            return "evidenceStatus=" + conjecture.evidenceStatus();
        }
        if (conjecture.supportCount() < 2 || conjecture.distinctAlphaSupport() < 2) {
            return "independent target-free support is insufficient";
        }
        if (conjecture.evidence().isEmpty()) {
            return "convergence evidence is empty";
        }
        return "";
    }

    private static String assumptionSignature(OpenTargetConjecture conjecture) {
        return conjecture.evidence().stream()
            .flatMap(evidence -> evidence.paths().stream())
            .flatMap(path -> path.assumptions().stream())
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().replaceAll("\\s+", " "))
            .distinct()
            .sorted()
            .reduce((left, right) -> left + "\u0001" + right)
            .orElse("");
    }

    private static List<String> splitAssumptions(String signature) {
        return signature.isBlank() ? List.of() : List.of(signature.split("\u0001", -1));
    }

    private static String canonicalMaterial(
        List<BridgeCluster> clusters,
        List<ClusterRejection> rejections
    ) {
        StringBuilder material = new StringBuilder(SCHEMA);
        clusters.forEach(cluster -> material.append("\ncluster=")
            .append(cluster.clusterId()).append('|')
            .append(cluster.alphaRelationHash()).append('|')
            .append(cluster.leftPattern()).append("->").append(cluster.rightPattern()).append('|')
            .append(cluster.graphRole()).append('|')
            .append(cluster.assumptions()).append('|')
            .append(cluster.families()).append('|')
            .append(cluster.conjectureIds()).append('|')
            .append(cluster.observationIds()).append('|')
            .append(cluster.distinctAlphaEvidence()).append('|')
            .append(cluster.distinctValueEvidence()));
        rejections.forEach(rejection -> material.append("\nrejection=")
            .append(rejection.scopeId()).append('|')
            .append(rejection.reason()).append('|')
            .append(rejection.detail()));
        return material.toString();
    }

    private static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record ClusterKey(
        String alphaRelationHash,
        String assumptionSignature,
        String graphRole
    ) implements Comparable<ClusterKey> {
        @Override
        public int compareTo(ClusterKey other) {
            int relation = alphaRelationHash.compareTo(other.alphaRelationHash);
            if (relation != 0) {
                return relation;
            }
            int assumptions = assumptionSignature.compareTo(other.assumptionSignature);
            return assumptions != 0 ? assumptions : graphRole.compareTo(other.graphRole);
        }
    }

    public record BridgeCluster(
        String clusterId,
        String alphaRelationHash,
        String leftPattern,
        String rightPattern,
        String graphRole,
        List<String> assumptions,
        List<String> families,
        List<String> conjectureIds,
        List<String> observationIds,
        int distinctAlphaEvidence,
        int distinctValueEvidence,
        String bridgeStatus,
        String validationStatus,
        String proofStatus,
        String noveltyStatus,
        String interestingnessStatus
    ) {
        public BridgeCluster {
            assumptions = List.copyOf(assumptions);
            families = List.copyOf(families);
            conjectureIds = List.copyOf(conjectureIds);
            observationIds = List.copyOf(observationIds);
        }
    }

    public record ClusterRejection(String scopeId, String reason, String detail) {
    }

    public record ClusterReport(
        String schema,
        List<BridgeCluster> clusters,
        List<ClusterRejection> rejections,
        String contentHash
    ) {
        public ClusterReport {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported cluster schema");
            }
            clusters = List.copyOf(clusters);
            rejections = List.copyOf(rejections);
            if (contentHash == null || !contentHash.startsWith("sha256:")) {
                throw new IllegalArgumentException("contentHash must be SHA-256");
            }
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("contentHash", contentHash)
                .array("clusters", array -> clusters.forEach(cluster ->
                    array.objectValue(object -> object
                        .property("clusterId", cluster.clusterId())
                        .property("alphaRelationHash", cluster.alphaRelationHash())
                        .property("leftPattern", cluster.leftPattern())
                        .property("rightPattern", cluster.rightPattern())
                        .property("graphRole", cluster.graphRole())
                        .stringArray("assumptions", cluster.assumptions())
                        .stringArray("families", cluster.families())
                        .stringArray("conjectureIds", cluster.conjectureIds())
                        .stringArray("observationIds", cluster.observationIds())
                        .property("distinctAlphaEvidence", cluster.distinctAlphaEvidence())
                        .property("distinctValueEvidence", cluster.distinctValueEvidence())
                        .property("bridgeStatus", cluster.bridgeStatus())
                        .property("validationStatus", cluster.validationStatus())
                        .property("proofStatus", cluster.proofStatus())
                        .property("noveltyStatus", cluster.noveltyStatus())
                        .property("interestingnessStatus", cluster.interestingnessStatus()))))
                .array("rejections", array -> rejections.forEach(rejection ->
                    array.objectValue(object -> object
                        .property("scopeId", rejection.scopeId())
                        .property("reason", rejection.reason())
                        .property("detail", rejection.detail()))))
                .endObject().toString();
        }
    }
}
