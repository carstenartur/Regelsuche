package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureMiner.PathEvidence;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Family- and rule-ID-blind structural clustering for open-target conjectures. */
public final class CrossFamilyStructuralClusterer {
    public static final String SCHEMA = "regelsuche.cross-family-structural-clusters/v1";
    private static final String GRAPH_ROLE = "EQUIVALENCE_PRESERVING_CONVERGENCE";

    private final RulePatternParser parser = new RulePatternParser();

    public ClusterReport cluster(List<OpenTargetConjecture> conjectures) {
        List<RejectedCandidate> rejected = new ArrayList<>();
        List<OpenTargetConjecture> supplied = conjectures == null ? List.of() : conjectures;
        supplied.stream().filter(Objects::isNull)
            .forEach(ignored -> rejected.add(new RejectedCandidate("", "candidate-is-null")));
        List<OpenTargetConjecture> ordered = supplied.stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(OpenTargetConjecture::conjectureId))
            .toList();

        Map<String, List<CandidateEvidence>> bySignature = new TreeMap<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (OpenTargetConjecture conjecture : ordered) {
            if (!seenIds.add(conjecture.conjectureId())) {
                rejected.add(new RejectedCandidate(
                    conjecture.conjectureId(), "duplicate-candidate-id"));
                continue;
            }
            try {
                CandidateEvidence evidence = evidence(conjecture);
                bySignature.computeIfAbsent(
                    evidence.structuralSignature(), ignored -> new ArrayList<>()).add(evidence);
            } catch (IllegalArgumentException exception) {
                rejected.add(new RejectedCandidate(
                    conjecture.conjectureId(), exception.getMessage()));
            }
        }

        List<BridgeCluster> clusters = bySignature.entrySet().stream()
            .map(entry -> toCluster(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(BridgeCluster::clusterId))
            .toList();
        List<RejectedCandidate> orderedRejected = rejected.stream()
            .sorted(Comparator.comparing(RejectedCandidate::candidateId)
                .thenComparing(RejectedCandidate::reason))
            .toList();
        return new ClusterReport(
            SCHEMA,
            clusters,
            orderedRejected,
            hash(canonicalMaterial(clusters, orderedRejected)));
    }

    private CandidateEvidence evidence(OpenTargetConjecture conjecture) {
        validate(conjecture);
        RulePatternNode left = parser.parse(conjecture.leftPattern());
        RulePatternNode right = parser.parse(conjecture.rightPattern());
        Map<String, String> alphaNames = new LinkedHashMap<>();
        String relationSignature = render(left, alphaNames) + "->" + render(right, alphaNames);
        List<String> assumptions = assumptions(conjecture);
        String structuralSignature = relationSignature
            + "|assumptions=" + String.join("\u0001", assumptions)
            + "|graphRole=" + GRAPH_ROLE;

        Set<String> observationIds = conjecture.evidence().stream()
            .map(ConvergenceEvidence::observationId)
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> alphaSupport = conjecture.evidence().stream()
            .map(ConvergenceEvidence::alphaPairFingerprint)
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> valueSupport = conjecture.evidence().stream()
            .map(ConvergenceEvidence::valuePairFingerprint)
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> families = new TreeSet<>();
        conjecture.postHocFamilies().stream()
            .filter(value -> value != null && !value.isBlank())
            .forEach(families::add);
        conjecture.evidence().stream()
            .map(ConvergenceEvidence::family)
            .filter(value -> value != null && !value.isBlank())
            .forEach(families::add);

        return new CandidateEvidence(
            conjecture.conjectureId(),
            structuralSignature,
            features(left, right, alphaNames, assumptions),
            assumptions,
            genericNeutralVariant(left, right),
            observationIds,
            alphaSupport,
            valueSupport,
            families);
    }

    private void validate(OpenTargetConjecture conjecture) {
        Objects.requireNonNull(conjecture, "conjecture");
        requireText(conjecture.conjectureId(), "conjectureId");
        requireText(conjecture.leftPattern(), "leftPattern");
        requireText(conjecture.rightPattern(), "rightPattern");
        if (!"OBSERVED_CONJECTURE".equals(conjecture.candidateStatus())
                || !"EQUIVALENCE_PRESERVING_CONVERGENT_PATHS".equals(
                    conjecture.evidenceStatus())) {
            throw new IllegalArgumentException("candidate-lacks-open-target-evidence");
        }
        if (conjecture.supportCount() < 2
                || conjecture.distinctAlphaSupport() < 2
                || conjecture.evidence().size() != conjecture.supportCount()) {
            throw new IllegalArgumentException("support-count-inconsistent");
        }
        Set<String> evidenceIds = conjecture.evidence().stream()
            .map(ConvergenceEvidence::observationId)
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> declaredIds = new TreeSet<>(conjecture.supportingObservationIds());
        Set<String> alphaSupport = conjecture.evidence().stream()
            .map(ConvergenceEvidence::alphaPairFingerprint)
            .collect(Collectors.toCollection(TreeSet::new));
        if (evidenceIds.size() != conjecture.supportCount()
                || !evidenceIds.equals(declaredIds)
                || alphaSupport.size() != conjecture.distinctAlphaSupport()) {
            throw new IllegalArgumentException("support-identities-inconsistent");
        }
        if (conjecture.evidence().stream()
                .anyMatch(item -> item.searchStatus() != GoalStatus.UNTARGETED)) {
            throw new IllegalArgumentException("targeted-evidence-not-allowed");
        }
        if (conjecture.evidence().stream().anyMatch(item ->
                item.paths().size() < 2
                    || item.paths().stream().anyMatch(path -> path.expressions().isEmpty()
                        || !path.expressions().getLast().equals(item.outputExpression())))) {
            throw new IllegalArgumentException("convergent-path-evidence-incomplete");
        }
    }

    private BridgeCluster toCluster(String signature, List<CandidateEvidence> evidence) {
        List<CandidateEvidence> ordered = evidence.stream()
            .sorted(Comparator.comparing(CandidateEvidence::candidateId))
            .toList();
        Set<String> candidateIds = ordered.stream()
            .map(CandidateEvidence::candidateId)
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> observations = ordered.stream()
            .flatMap(item -> item.observationIds().stream())
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> alphaSupport = ordered.stream()
            .flatMap(item -> item.alphaSupport().stream())
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> valueSupport = ordered.stream()
            .flatMap(item -> item.valueSupport().stream())
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> alphaProfiles = ordered.stream()
            .map(item -> String.join("\u0001", item.alphaSupport()))
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> valueProfiles = ordered.stream()
            .map(item -> String.join("\u0001", item.valueSupport()))
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> families = ordered.stream()
            .flatMap(item -> item.postHocFamilies().stream())
            .collect(Collectors.toCollection(TreeSet::new));
        boolean generic = ordered.stream().anyMatch(CandidateEvidence::genericNeutralVariant);
        ClusterStatus status = status(
            generic,
            candidateIds.size(),
            families.size(),
            alphaProfiles.size(),
            valueProfiles.size());
        CandidateEvidence representative = ordered.getFirst();
        return new BridgeCluster(
            "structural-cluster-" + sha256(signature).substring(0, 20),
            "sha256:" + sha256(signature),
            signature,
            representative.namedFeatures(),
            representative.assumptions(),
            List.copyOf(candidateIds),
            List.copyOf(observations),
            alphaSupport.size(),
            valueSupport.size(),
            alphaProfiles.size(),
            valueProfiles.size(),
            List.copyOf(families),
            status,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            explanation(status));
    }

    private ClusterStatus status(
        boolean generic,
        int candidateCount,
        int familyCount,
        int independentAlphaProfiles,
        int independentValueProfiles
    ) {
        if (generic) {
            return ClusterStatus.GENERIC_NORMALIZATION;
        }
        if (candidateCount < 2) {
            return ClusterStatus.SINGLE_CANDIDATE;
        }
        if (familyCount < 2) {
            return ClusterStatus.SINGLE_FAMILY;
        }
        if (independentAlphaProfiles < 2) {
            return ClusterStatus.ALPHA_RENAME_ONLY;
        }
        if (independentValueProfiles < 2) {
            return ClusterStatus.DUPLICATE_VALUE_ONLY;
        }
        return ClusterStatus.ELIGIBLE_BRIDGE;
    }

    private List<String> features(
        RulePatternNode left,
        RulePatternNode right,
        Map<String, String> alphaNames,
        List<String> assumptions
    ) {
        Map<String, Integer> leftPlaceholders = placeholderOccurrences(left, alphaNames);
        Map<String, Integer> rightPlaceholders = placeholderOccurrences(right, alphaNames);
        return List.of(
            "rootTransition=" + root(left) + "->" + root(right),
            "nodeCount=" + nodeCount(left) + "->" + nodeCount(right),
            "nodeDelta=" + (nodeCount(right) - nodeCount(left)),
            "placeholderOccurrences=" + formatCounts(leftPlaceholders)
                + "->" + formatCounts(rightPlaceholders),
            "operatorHistogram=" + formatCounts(operatorHistogram(left))
                + "->" + formatCounts(operatorHistogram(right)),
            "assumptionCount=" + assumptions.size(),
            "graphRole=" + GRAPH_ROLE);
    }

    private List<String> assumptions(OpenTargetConjecture conjecture) {
        return conjecture.evidence().stream()
            .flatMap(evidence -> evidence.paths().stream())
            .flatMap(path -> path.assumptions().stream())
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().replaceAll("\\s+", " "))
            .distinct()
            .sorted()
            .toList();
    }

    private Map<String, Integer> placeholderOccurrences(
        RulePatternNode node,
        Map<String, String> alphaNames
    ) {
        Map<String, Integer> counts = new TreeMap<>();
        collectPlaceholders(node, alphaNames, counts);
        return counts;
    }

    private void collectPlaceholders(
        RulePatternNode node,
        Map<String, String> alphaNames,
        Map<String, Integer> counts
    ) {
        if (node instanceof PatternVariable variable) {
            String alpha = alphaNames.computeIfAbsent(
                variable.name(), ignored -> "p" + alphaNames.size());
            counts.merge(alpha, 1, Integer::sum);
            return;
        }
        children(node).forEach(child -> collectPlaceholders(child, alphaNames, counts));
    }

    private Map<String, Integer> operatorHistogram(RulePatternNode node) {
        Map<String, Integer> counts = new TreeMap<>();
        collectOperators(node, counts);
        return counts;
    }

    private void collectOperators(RulePatternNode node, Map<String, Integer> counts) {
        if (node instanceof PatternBinary binary) {
            counts.merge("BINARY:" + binary.op().name(), 1, Integer::sum);
        } else if (node instanceof PatternFunction function) {
            counts.merge("FUNCTION:" + function.name(), 1, Integer::sum);
        }
        children(node).forEach(child -> collectOperators(child, counts));
    }

    private String render(RulePatternNode node, Map<String, String> alphaNames) {
        if (node instanceof PatternVariable variable) {
            return "P:" + alphaNames.computeIfAbsent(
                variable.name(), ignored -> "p" + alphaNames.size());
        }
        if (node instanceof PatternNumber number) {
            return "N:" + number.value();
        }
        if (node instanceof PatternFunction function) {
            return "F:" + function.name() + "(" + function.arguments().stream()
                .map(argument -> render(argument, alphaNames))
                .collect(Collectors.joining(",")) + ")";
        }
        PatternBinary binary = (PatternBinary) node;
        return "B:" + binary.op().name() + "("
            + render(binary.left(), alphaNames) + ","
            + render(binary.right(), alphaNames) + ")";
    }

    private boolean genericNeutralVariant(RulePatternNode left, RulePatternNode right) {
        return neutralReduction(left, right) || neutralReduction(right, left);
    }

    private boolean neutralReduction(RulePatternNode complex, RulePatternNode simple) {
        if (!(complex instanceof PatternBinary binary)) {
            return false;
        }
        return switch (binary.op()) {
            case ADD -> number(binary.left(), 0) && sameAlpha(binary.right(), simple)
                || number(binary.right(), 0) && sameAlpha(binary.left(), simple);
            case MUL -> number(binary.left(), 1) && sameAlpha(binary.right(), simple)
                || number(binary.right(), 1) && sameAlpha(binary.left(), simple);
            case SUB -> number(binary.right(), 0) && sameAlpha(binary.left(), simple);
            case DIV -> number(binary.right(), 1) && sameAlpha(binary.left(), simple);
            case POW -> false;
        };
    }

    private boolean sameAlpha(RulePatternNode left, RulePatternNode right) {
        return render(left, new LinkedHashMap<>()).equals(
            render(right, new LinkedHashMap<>()));
    }

    private boolean number(RulePatternNode node, int value) {
        return node instanceof PatternNumber number && number.value() == value;
    }

    private String root(RulePatternNode node) {
        if (node instanceof PatternVariable) {
            return "PLACEHOLDER";
        }
        if (node instanceof PatternNumber) {
            return "NUMBER";
        }
        if (node instanceof PatternFunction function) {
            return "FUNCTION:" + function.name();
        }
        return "BINARY:" + ((PatternBinary) node).op().name();
    }

    private int nodeCount(RulePatternNode node) {
        return 1 + children(node).stream().mapToInt(this::nodeCount).sum();
    }

    private List<RulePatternNode> children(RulePatternNode node) {
        if (node instanceof PatternBinary binary) {
            return List.of(binary.left(), binary.right());
        }
        if (node instanceof PatternFunction function) {
            return function.arguments();
        }
        return List.of();
    }

    private String formatCounts(Map<String, Integer> counts) {
        return counts.entrySet().stream()
            .map(entry -> entry.getKey() + ":" + entry.getValue())
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String explanation(ClusterStatus status) {
        return switch (status) {
            case ELIGIBLE_BRIDGE -> "structural support spans multiple post-hoc families";
            case SINGLE_CANDIDATE -> "only one independently formed candidate has this structure";
            case SINGLE_FAMILY -> "all support belongs to one post-hoc family";
            case ALPHA_RENAME_ONLY -> "candidate support profiles differ only by alpha renaming";
            case DUPLICATE_VALUE_ONLY -> "candidate support profiles repeat the same value evidence";
            case GENERIC_NORMALIZATION -> "structure is explained by a neutral-element normalization";
        };
    }

    private String canonicalMaterial(
        List<BridgeCluster> clusters,
        List<RejectedCandidate> rejected
    ) {
        StringBuilder material = new StringBuilder(SCHEMA);
        clusters.forEach(cluster -> material.append("\ncluster=")
            .append(cluster.clusterId()).append('|')
            .append(cluster.structuralSignatureHash()).append('|')
            .append(cluster.structuralSignature()).append('|')
            .append(cluster.namedFeatures()).append('|')
            .append(cluster.assumptions()).append('|')
            .append(cluster.candidateIds()).append('|')
            .append(cluster.supportingObservationIds()).append('|')
            .append(cluster.distinctAlphaSupport()).append('|')
            .append(cluster.distinctValueSupport()).append('|')
            .append(cluster.independentAlphaProfiles()).append('|')
            .append(cluster.independentValueProfiles()).append('|')
            .append(cluster.postHocFamilies()).append('|')
            .append(cluster.status()).append('|')
            .append(cluster.explanation()));
        rejected.forEach(item -> material.append("\nrejection=")
            .append(item.candidateId()).append('|').append(item.reason()));
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

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "-missing");
        }
    }

    public enum ClusterStatus {
        ELIGIBLE_BRIDGE,
        SINGLE_CANDIDATE,
        SINGLE_FAMILY,
        ALPHA_RENAME_ONLY,
        DUPLICATE_VALUE_ONLY,
        GENERIC_NORMALIZATION
    }

    public record ClusterReport(
        String schema,
        List<BridgeCluster> clusters,
        List<RejectedCandidate> rejectedCandidates,
        String contentHash
    ) {
        public ClusterReport {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported cluster schema");
            }
            clusters = clusters == null ? List.of() : List.copyOf(clusters);
            rejectedCandidates = rejectedCandidates == null
                ? List.of()
                : List.copyOf(rejectedCandidates);
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
                        .property("structuralSignatureHash", cluster.structuralSignatureHash())
                        .property("structuralSignature", cluster.structuralSignature())
                        .stringArray("namedFeatures", cluster.namedFeatures())
                        .stringArray("assumptions", cluster.assumptions())
                        .stringArray("candidateIds", cluster.candidateIds())
                        .stringArray("supportingObservationIds", cluster.supportingObservationIds())
                        .property("distinctAlphaSupport", cluster.distinctAlphaSupport())
                        .property("distinctValueSupport", cluster.distinctValueSupport())
                        .property("independentAlphaProfiles", cluster.independentAlphaProfiles())
                        .property("independentValueProfiles", cluster.independentValueProfiles())
                        .stringArray("postHocFamilies", cluster.postHocFamilies())
                        .property("status", cluster.status().name())
                        .property("validationStatus", cluster.validationStatus())
                        .property("proofStatus", cluster.proofStatus())
                        .property("noveltyStatus", cluster.noveltyStatus())
                        .property("interestingnessStatus", cluster.interestingnessStatus())
                        .property("explanation", cluster.explanation()))))
                .array("rejectedCandidates", array -> rejectedCandidates.forEach(item ->
                    array.objectValue(object -> object
                        .property("candidateId", item.candidateId())
                        .property("reason", item.reason()))))
                .endObject().toString();
        }
    }

    public record BridgeCluster(
        String clusterId,
        String structuralSignatureHash,
        String structuralSignature,
        List<String> namedFeatures,
        List<String> assumptions,
        List<String> candidateIds,
        List<String> supportingObservationIds,
        int distinctAlphaSupport,
        int distinctValueSupport,
        int independentAlphaProfiles,
        int independentValueProfiles,
        List<String> postHocFamilies,
        ClusterStatus status,
        String validationStatus,
        String proofStatus,
        String noveltyStatus,
        String interestingnessStatus,
        String explanation
    ) {
        public BridgeCluster {
            namedFeatures = namedFeatures == null ? List.of() : List.copyOf(namedFeatures);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
            supportingObservationIds = supportingObservationIds == null
                ? List.of()
                : List.copyOf(supportingObservationIds);
            postHocFamilies = postHocFamilies == null ? List.of() : List.copyOf(postHocFamilies);
            Objects.requireNonNull(status, "status");
            validationStatus = validationStatus == null ? "NOT_EVALUATED" : validationStatus;
            proofStatus = proofStatus == null ? "NOT_EVALUATED" : proofStatus;
            noveltyStatus = noveltyStatus == null ? "NOT_EVALUATED" : noveltyStatus;
            interestingnessStatus = interestingnessStatus == null
                ? "NOT_EVALUATED"
                : interestingnessStatus;
            explanation = explanation == null ? "" : explanation;
        }
    }

    public record RejectedCandidate(String candidateId, String reason) {
        public RejectedCandidate {
            candidateId = candidateId == null ? "" : candidateId;
            reason = reason == null ? "" : reason;
        }
    }

    private record CandidateEvidence(
        String candidateId,
        String structuralSignature,
        List<String> namedFeatures,
        List<String> assumptions,
        boolean genericNeutralVariant,
        Set<String> observationIds,
        Set<String> alphaSupport,
        Set<String> valueSupport,
        Set<String> postHocFamilies
    ) {
    }
}
