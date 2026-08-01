package de.regelsuche.search.program;

import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One lazily composed rewrite candidate produced while interpreting a
 * {@link RewriteProgram}.
 */
public record RewriteCandidate(
    String originNodeId,
    String inputExpression,
    String outputExpression,
    List<Transformation> steps
) {
    public RewriteCandidate {
        originNodeId = requireText(originNodeId, "originNodeId");
        inputExpression = requireText(inputExpression, "inputExpression");
        outputExpression = requireText(outputExpression, "outputExpression");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
    }

    public List<String> ruleIds() {
        return steps.stream().map(Transformation::rule).toList();
    }

    /** Ordered primitive lineage, including repeated applications. */
    public List<String> primitiveRuleIds() {
        return steps.stream()
            .flatMap(step -> step.primitiveRuleIds().stream())
            .toList();
    }

    public Transformation lastStep() {
        return steps.get(steps.size() - 1);
    }

    public RewriteCandidate append(RewriteCandidate suffix, String newOriginNodeId) {
        Objects.requireNonNull(suffix, "suffix");
        if (!outputExpression.equals(suffix.inputExpression())) {
            throw new IllegalArgumentException(
                "candidate chain is discontinuous: " + outputExpression
                    + " != " + suffix.inputExpression());
        }
        List<Transformation> combined = new ArrayList<>(steps);
        combined.addAll(suffix.steps());
        return new RewriteCandidate(
            requireText(newOriginNodeId, "newOriginNodeId"),
            inputExpression,
            suffix.outputExpression(),
            combined
        );
    }

    public RewriteCandidate withOriginNodeId(String newOriginNodeId) {
        return new RewriteCandidate(
            newOriginNodeId,
            inputExpression,
            outputExpression,
            steps);
    }

    /**
     * Converts the path into the ordinary transformation model consumed by all
     * existing search strategies. Primitive candidates are preserved exactly;
     * multi-step candidates become explicit macro-like transformations whose
     * structured primitive lineage remains visible to work-aware search.
     */
    public Transformation toTransformation() {
        if (steps.size() == 1) {
            return steps.get(0);
        }

        RewriteKind kind = combinedKind();
        boolean mayIncreaseComplexity = steps.stream()
            .anyMatch(Transformation::mayIncreaseComplexity);
        boolean equivalencePreserving = steps.stream()
            .allMatch(Transformation::equivalencePreservingByConstruction);
        int estimatedCostDelta = steps.stream()
            .mapToInt(Transformation::estimatedCostDelta)
            .reduce(0, RewriteCandidate::saturatedAdd);
        List<String> assumptions = steps.stream()
            .flatMap(step -> step.assumptions().stream())
            .toList();

        String rule = "program:" + originNodeId + "["
            + String.join(" -> ", ruleIds()) + "]";
        String applicationKey = "program:" + originNodeId + ":"
            + steps.stream().map(Transformation::applicationKey)
                .reduce((left, right) -> left + "->" + right)
                .orElseThrow();

        return new Transformation(
            rule,
            outputExpression,
            kind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            equivalencePreserving,
            applicationKey,
            assumptions,
            combinedValue(steps.stream().map(Transformation::packId).toList()),
            combinedValue(steps.stream().map(Transformation::license).toList()),
            primitiveRuleIds()
        );
    }

    String fingerprint() {
        return outputExpression + "\u0000"
            + steps.stream().map(Transformation::applicationKey)
                .reduce((left, right) -> left + "\u0001" + right)
                .orElse("");
    }

    private RewriteKind combinedKind() {
        RewriteKind first = steps.get(0).kind();
        return steps.stream().allMatch(step -> step.kind() == first)
            ? first
            : RewriteKind.NORMALIZE;
    }

    private static int saturatedAdd(int left, int right) {
        long sum = (long) left + right;
        if (sum > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (sum < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) sum;
    }

    private static String combinedValue(List<String> values) {
        Set<String> distinct = new LinkedHashSet<>(values);
        return String.join("+", distinct);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
