package de.regelsuche.discovery.representation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * AST-occurrence-preserving opportunity to replace repeated semantic values by
 * an explicit shared definition.
 *
 * <p>This evidence does not introduce an alias into the mathematical language
 * and does not claim equivalence or usefulness. It records the exact repeated AST
 * occurrences and charges both definition and reference costs so an opaque
 * symbol cannot manufacture compression credit.</p>
 */
public record RepeatedStructureExtractionCandidate(
    String identity,
    String sourceExpression,
    String semanticValueKey,
    String representativeExpression,
    List<Occurrence> occurrences,
    Policy policy,
    SharingCost sharingCost
) implements Comparable<RepeatedStructureExtractionCandidate> {
    public static final String CANDIDATE_TYPE =
        RepresentationCandidateAssessment.TYPE_REPEATED_STRUCTURE_EXTRACTION;
    private static final String IDENTITY_REVISION =
        "regelsuche.repeated-structure-candidate/v1";

    public RepeatedStructureExtractionCandidate {
        sourceExpression = RepresentationCandidateAssessment.requireText(
            sourceExpression, "sourceExpression");
        semanticValueKey = RepresentationCandidateAssessment.requireText(
            semanticValueKey, "semanticValueKey");
        representativeExpression = RepresentationCandidateAssessment.requireText(
            representativeExpression, "representativeExpression");
        policy = Objects.requireNonNull(policy, "policy");
        sharingCost = Objects.requireNonNull(sharingCost, "sharingCost");
        occurrences = Objects.requireNonNull(occurrences, "occurrences").stream()
            .map(occurrence -> Objects.requireNonNull(
                occurrence, "occurrence"))
            .sorted()
            .toList();
        validateOccurrences(occurrences, policy);
        Occurrence representative = representative(occurrences);
        if (!representative.expression().equals(representativeExpression)) {
            throw new IllegalArgumentException(
                "representativeExpression is not the canonical occurrence");
        }
        SharingCost expectedCost = SharingCost.calculate(
            occurrences, representative, policy);
        if (!expectedCost.equals(sharingCost)) {
            throw new IllegalArgumentException(
                "sharingCost does not balance with occurrences and policy");
        }
        String expectedIdentity = identityFor(
            sourceExpression,
            semanticValueKey,
            representativeExpression,
            occurrences,
            policy,
            sharingCost
        );
        if (!expectedIdentity.equals(identity)) {
            throw new IllegalArgumentException(
                "identity does not match repeated-structure evidence");
        }
    }

    public static RepeatedStructureExtractionCandidate create(
        String sourceExpression,
        String semanticValueKey,
        List<Occurrence> occurrences,
        Policy policy
    ) {
        Objects.requireNonNull(occurrences, "occurrences");
        Objects.requireNonNull(policy, "policy");
        List<Occurrence> snapshot = occurrences.stream()
            .map(occurrence -> Objects.requireNonNull(
                occurrence, "occurrence"))
            .sorted()
            .toList();
        validateOccurrences(snapshot, policy);
        Occurrence representative = representative(snapshot);
        SharingCost cost = SharingCost.calculate(
            snapshot, representative, policy);
        String normalizedSource = RepresentationCandidateAssessment.requireText(
            sourceExpression, "sourceExpression");
        String normalizedValueKey = RepresentationCandidateAssessment.requireText(
            semanticValueKey, "semanticValueKey");
        String identity = identityFor(
            normalizedSource,
            normalizedValueKey,
            representative.expression(),
            snapshot,
            policy,
            cost
        );
        return new RepeatedStructureExtractionCandidate(
            identity,
            normalizedSource,
            normalizedValueKey,
            representative.expression(),
            snapshot,
            policy,
            cost
        );
    }

    public String candidateType() {
        return CANDIDATE_TYPE;
    }

    public boolean material() {
        return sharingCost.material();
    }

    @Override
    public int compareTo(RepeatedStructureExtractionCandidate other) {
        Objects.requireNonNull(other, "other");
        int materialComparison = Boolean.compare(
            other.material(), material());
        if (materialComparison != 0) {
            return materialComparison;
        }
        int savingsComparison = Integer.compare(
            other.sharingCost().netAstNodeSavings(),
            sharingCost.netAstNodeSavings());
        if (savingsComparison != 0) {
            return savingsComparison;
        }
        int representativeComparison = representativeExpression.compareTo(
            other.representativeExpression());
        return representativeComparison != 0
            ? representativeComparison
            : identity.compareTo(other.identity());
    }

    private static void validateOccurrences(
        List<Occurrence> occurrences,
        Policy policy
    ) {
        if (occurrences.size() < policy.minimumOccurrences()) {
            throw new IllegalArgumentException(
                "too few repeated-structure occurrences");
        }
        Set<ExpressionOccurrencePath> paths = new HashSet<>();
        for (Occurrence occurrence : occurrences) {
            if (occurrence.astNodeCount() < policy.minimumSubtreeNodes()) {
                throw new IllegalArgumentException(
                    "occurrence is smaller than the policy minimum");
            }
            if (!paths.add(occurrence.path())) {
                throw new IllegalArgumentException(
                    "duplicate repeated-structure occurrence path: "
                        + occurrence.path());
            }
        }
    }

    private static Occurrence representative(List<Occurrence> occurrences) {
        return occurrences.stream()
            .min(Comparator
                .comparingInt(Occurrence::astNodeCount)
                .thenComparing(Occurrence::expression)
                .thenComparing(Occurrence::path))
            .orElseThrow();
    }

    private static String identityFor(
        String sourceExpression,
        String semanticValueKey,
        String representativeExpression,
        List<Occurrence> occurrences,
        Policy policy,
        SharingCost cost
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, IDENTITY_REVISION);
        append(descriptor, sourceExpression);
        append(descriptor, semanticValueKey);
        append(descriptor, representativeExpression);
        append(descriptor, policy.contentHash());
        append(descriptor, Integer.toString(occurrences.size()));
        for (Occurrence occurrence : occurrences) {
            append(descriptor, occurrence.path().canonical());
            append(descriptor, occurrence.expression());
            append(descriptor, Integer.toString(occurrence.astNodeCount()));
        }
        append(descriptor, cost.canonicalDescriptor());
        return sha256(descriptor.toString());
    }

    private static void append(StringBuilder descriptor, String value) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(value, "value");
        descriptor.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** One exact AST occurrence and its deterministic rendered expression. */
    public record Occurrence(
        ExpressionOccurrencePath path,
        String expression,
        int astNodeCount
    ) implements Comparable<Occurrence> {
        public Occurrence {
            path = Objects.requireNonNull(path, "path");
            expression = RepresentationCandidateAssessment.requireText(
                expression, "expression");
            if (astNodeCount < 1) {
                throw new IllegalArgumentException(
                    "astNodeCount must be positive");
            }
        }

        @Override
        public int compareTo(Occurrence other) {
            Objects.requireNonNull(other, "other");
            int pathComparison = path.compareTo(other.path());
            if (pathComparison != 0) {
                return pathComparison;
            }
            int expressionComparison = expression.compareTo(other.expression());
            return expressionComparison != 0
                ? expressionComparison
                : Integer.compare(astNodeCount, other.astNodeCount());
        }
    }

    /** Frozen anti-alias-gaming cost policy. */
    public record Policy(
        int minimumOccurrences,
        int minimumSubtreeNodes,
        int bindingNodeCost,
        int referenceNodeCost,
        int minimumNetSavings
    ) {
        private static final String REVISION =
            "regelsuche.repeated-structure-policy/v1";

        public Policy {
            if (minimumOccurrences < 2) {
                throw new IllegalArgumentException(
                    "minimumOccurrences must be at least two");
            }
            if (minimumSubtreeNodes < 2) {
                throw new IllegalArgumentException(
                    "minimumSubtreeNodes must exclude leaves");
            }
            if (bindingNodeCost < 1 || referenceNodeCost < 1) {
                throw new IllegalArgumentException(
                    "binding and reference costs must be positive");
            }
            if (minimumNetSavings < 1) {
                throw new IllegalArgumentException(
                    "minimumNetSavings must be positive");
            }
        }

        public static Policy standard() {
            return new Policy(2, 2, 1, 1, 1);
        }

        public String contentHash() {
            StringBuilder descriptor = new StringBuilder();
            append(descriptor, REVISION);
            append(descriptor, Integer.toString(minimumOccurrences));
            append(descriptor, Integer.toString(minimumSubtreeNodes));
            append(descriptor, Integer.toString(bindingNodeCost));
            append(descriptor, Integer.toString(referenceNodeCost));
            append(descriptor, Integer.toString(minimumNetSavings));
            return sha256(descriptor.toString());
        }
    }

    /** Raw tree-versus-explicit-sharing dimensions. */
    public record SharingCost(
        int repeatedTreeCost,
        int definitionTreeCost,
        int referenceTreeCost,
        int explicitSharingTreeCost,
        int netAstNodeSavings,
        int minimumNetSavings,
        boolean material
    ) {
        public SharingCost {
            if (repeatedTreeCost < 1 || definitionTreeCost < 1
                    || referenceTreeCost < 1
                    || explicitSharingTreeCost < 1
                    || minimumNetSavings < 1) {
                throw new IllegalArgumentException(
                    "sharing costs and threshold must be positive");
            }
            if (explicitSharingTreeCost
                    != definitionTreeCost + referenceTreeCost) {
                throw new IllegalArgumentException(
                    "explicit sharing cost does not balance");
            }
            if (netAstNodeSavings
                    != repeatedTreeCost - explicitSharingTreeCost) {
                throw new IllegalArgumentException(
                    "net savings do not balance");
            }
            if (material != (netAstNodeSavings >= minimumNetSavings)) {
                throw new IllegalArgumentException(
                    "material flag does not match the frozen threshold");
            }
        }

        private static SharingCost calculate(
            List<Occurrence> occurrences,
            Occurrence representative,
            Policy policy
        ) {
            int repeatedCost = occurrences.stream()
                .mapToInt(Occurrence::astNodeCount)
                .reduce(0, Math::addExact);
            int definitionCost = Math.addExact(
                representative.astNodeCount(), policy.bindingNodeCost());
            int referencesCost = Math.multiplyExact(
                occurrences.size(), policy.referenceNodeCost());
            int explicitCost = Math.addExact(
                definitionCost, referencesCost);
            int netSavings = Math.subtractExact(
                repeatedCost, explicitCost);
            return new SharingCost(
                repeatedCost,
                definitionCost,
                referencesCost,
                explicitCost,
                netSavings,
                policy.minimumNetSavings(),
                netSavings >= policy.minimumNetSavings()
            );
        }

        private String canonicalDescriptor() {
            StringBuilder descriptor = new StringBuilder();
            append(descriptor, Integer.toString(repeatedTreeCost));
            append(descriptor, Integer.toString(definitionTreeCost));
            append(descriptor, Integer.toString(referenceTreeCost));
            append(descriptor, Integer.toString(explicitSharingTreeCost));
            append(descriptor, Integer.toString(netAstNodeSavings));
            append(descriptor, Integer.toString(minimumNetSavings));
            append(descriptor, Boolean.toString(material));
            return descriptor.toString();
        }
    }
}
