package de.regelsuche.transform;

import de.regelsuche.assumption.AssumptionSignature;
import java.util.List;

public record Transformation(
    String rule,
    String transformedExpression,
    RewriteKind kind,
    boolean mayIncreaseComplexity,
    int estimatedCostDelta,
    boolean equivalencePreservingByConstruction,
    String applicationKey,
    List<String> assumptions,
    String packId,
    String license,
    List<String> primitiveRuleIds
) {
    public Transformation(String rule, String transformedExpression) {
        this(
            rule,
            transformedExpression,
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            rule + ":" + transformedExpression);
    }

    public Transformation(
        String rule,
        String transformedExpression,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        String applicationKey
    ) {
        this(
            rule,
            transformedExpression,
            kind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            equivalencePreservingByConstruction,
            applicationKey,
            List.of());
    }

    public Transformation(
        String rule,
        String transformedExpression,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        String applicationKey,
        List<String> assumptions
    ) {
        this(
            rule,
            transformedExpression,
            kind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            equivalencePreservingByConstruction,
            applicationKey,
            assumptions,
            "core",
            "PROJECT");
    }

    /** Compatibility constructor: one ordinary transformation is one primitive. */
    public Transformation(
        String rule,
        String transformedExpression,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        String applicationKey,
        List<String> assumptions,
        String packId,
        String license
    ) {
        this(
            rule,
            transformedExpression,
            kind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            equivalencePreservingByConstruction,
            applicationKey,
            assumptions,
            packId,
            license,
            List.of(rule));
    }

    public Transformation {
        if (rule == null || rule.isBlank()
                || transformedExpression == null
                || transformedExpression.isBlank()
                || kind == null
                || applicationKey == null
                || applicationKey.isBlank()) {
            throw new IllegalArgumentException(
                "rule, kind, applicationKey and transformedExpression "
                    + "must not be blank");
        }
        assumptions = AssumptionSignature.ofExpressions(assumptions)
            .normalizedAssumptions();
        packId = packId == null || packId.isBlank() ? "core" : packId;
        license = license == null || license.isBlank() ? "PROJECT" : license;
        if (primitiveRuleIds == null || primitiveRuleIds.isEmpty()) {
            throw new IllegalArgumentException(
                "primitiveRuleIds must contain at least one retained step");
        }
        primitiveRuleIds = primitiveRuleIds.stream()
            .map(value -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(
                        "primitive rule IDs must not be blank");
                }
                return value.trim();
            })
            .toList();
    }

    /** Number of actual primitive rewrites represented by this search edge. */
    public int primitiveStepCount() {
        return primitiveRuleIds.size();
    }
}
