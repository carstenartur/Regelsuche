package de.regelsuche.mining;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
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

    private final RulePatternParser parser = new RulePatternParser();

    public ClusterReport cluster(List<OpenTargetConjecture> conjectures) {
        List<OpenTargetConjecture> ordered = conjectures == null
            ? List.of()
            : conjectures.stream()
                .sorted(Comparator.comparing(OpenTargetConjecture::conjectureId))
                .toList();
        Map<String, List<CandidateEvidence>> bySignature = new TreeMap<>();
        List<RejectedCandidate> rejected = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (OpenTargetConjecture conjecture : ordered) {
            if (conjecture == null) {
                rejected.add(new RejectedCandidate("", "candidate-is-null"));
                continue;
            }
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
        return new ClusterReport(
            SCHEMA,
            clusters,
            rejected.stream()
                .sorted(Comparator.comparing(RejectedCandidate::candidateId)
                    .thenComparing(RejectedCandidate::reason))
                .toList());
    }

    private CandidateEvidence evidence(OpenTargetConjecture conjecture) {
        validate(conjecture);
        RulePatternNode left = parser.parse(conjecture.leftPattern());
        RulePatternNode right = parser.parse(conjecture.rightPattern());
        Map<String, String> alphaNames = new LinkedHashMap<>();
        String alphaLeft = render(left, alphaNames);
        String alphaRight = render(right, alphaNames);
        String signature = alphaLeft + "->" + alphaRight;

        Set<String> observations = conjecture.evidence().stream()
            .map(ConvergenceEvidence::observationId)
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> alphaSupport = conjecture.evidence().stream()
            .map(ConvergenceEvidence::alphaPairFingerprint)
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> valueSupport = conjecture.evidence().stream()
            .map(ConvergenceEvidence::valuePairFingerprint)
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> families = new TreeSet<>(conjecture.postHocFamilies());
        conjecture.evidence().stream()
            .map(ConvergenceEvidence::family)
            .filter(value -> value != null && !value.isBlank())
            .forEach(families::add);

        return new CandidateEvidence(
            conjecture.conjectureId(),
            signature,
            features(left, right, alphaNames),
            genericNeutralVariant(left, right),
            observations,
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
                || conjecture.evidence().size() != conjecture.supportCount()) {
            throw new IllegalArgumentException("support-count-inconsistent");
        }
        Set<String> evidenceIds = conjecture.evidence().stream()
            .map(ConvergenceEvidence::observationId)
            .collect(Collectors.toCollection(TreeSet::new));
        if (evidenceIds.size() != conjecture.supportCount()
                || !evidenceIds.equals(new TreeSet<>(conjecture.supportingObservationIds()))) {
            throw new IllegalArgumentException("support-identities-inconsistent");
        }
        if (conjecture.evidence().stream()
                .anyMatch(item -> item.searchStatus() != GoalStatus.UNTARGETED)) {
            throw new IllegalArgumentException("targeted-evidence-not-allowed");
        }
        if (conjecture.evidence().stream().anyMatch(item -> item.paths().isEmpty())) {
            throw new IllegalArgumentException("path-evidence-missing");
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
        Set<String> families = ordered.stream()
            .flatMap(item -> item.postHocFamilies().stream())
            .collect(Collectors.toCollection(TreeSet::new));
        boolean generic = ordered.stream().anyMatch(CandidateEvidence::genericNeutralVariant);
        ClusterStatus status = status(generic, candidateIds.size(), families.size(), alphaSupport.size());
        return new BridgeCluster(
            "structural-cluster-" + sha256(signature).substring(0, 20),
            "sha256:" + sha256(signature),
            signature,
            ordered.getFirst().namedFeatures(),
            List.copyOf(candidateIds),
            List.copyOf(observations),
            alphaSupport.size(),
            valueSupport.size(),
            List.copyOf(families),
            status,
            explanation(status));
    }

    private ClusterStatus status(
        boolean generic,
        int candidateCount,
        int familyCount,
        int distinctAlphaSupport
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
        if (distinctAlphaSupport < 2) {
            return ClusterStatus.ALPHA_RENAME_ONLY;
        }
        return ClusterStatus.ELIGIBLE_BRIDGE;
    }

    private List<String> features(
        RulePatternNode left,
        RulePatternNode right,
        Map<String, String> alphaNames
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
                + "->" + formatCounts(operatorHistogram(right)));
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
            case ALPHA_RENAME_ONLY -> "support does not exceed alpha-renaming diversity";
            case GENERIC_NORMALIZATION -> "structure is explained by a neutral-element normalization";
        };
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
        GENERIC_NORMALIZATION
    }

    public record ClusterReport(
        String schema,
        List<BridgeCluster> clusters,
        List<RejectedCandidate> rejectedCandidates
    ) {
        public ClusterReport {
            clusters = clusters == null ? List.of() : List.copyOf(clusters);
            rejectedCandidates = rejectedCandidates == null
                ? List.of()
                : List.copyOf(rejectedCandidates);
        }
    }

    public record BridgeCluster(
        String clusterId,
        String structuralSignatureHash,
        String structuralSignature,
        List<String> namedFeatures,
        List<String> candidateIds,
        List<String> supportingObservationIds,
        int distinctAlphaSupport,
        int distinctValueSupport,
        List<String> postHocFamilies,
        ClusterStatus status,
        String explanation
    ) {
        public BridgeCluster {
            namedFeatures = namedFeatures == null ? List.of() : List.copyOf(namedFeatures);
            candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
            supportingObservationIds = supportingObservationIds == null
                ? List.of()
                : List.copyOf(supportingObservationIds);
            postHocFamilies = postHocFamilies == null ? List.of() : List.copyOf(postHocFamilies);
            Objects.requireNonNull(status, "status");
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
        boolean genericNeutralVariant,
        Set<String> observationIds,
        Set<String> alphaSupport,
        Set<String> valueSupport,
        Set<String> postHocFamilies
    ) {
    }
}
