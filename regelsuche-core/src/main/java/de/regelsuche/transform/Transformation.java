package de.regelsuche.transform;

public record Transformation(
    String rule,
    String transformedExpression,
    RewriteKind kind,
    boolean mayIncreaseComplexity,
    int estimatedCostDelta,
    boolean equivalencePreservingByConstruction,
    String applicationKey
) {
    public Transformation(String rule, String transformedExpression) {
        this(rule, transformedExpression, RewriteKind.NORMALIZE, false, 0, true, rule + ":" + transformedExpression);
    }

    public Transformation {
        if (rule == null || rule.isBlank() || transformedExpression == null || transformedExpression.isBlank()
            || kind == null || applicationKey == null || applicationKey.isBlank()) {
            throw new IllegalArgumentException("rule, kind, applicationKey and transformedExpression must not be blank");
        }
    }
}
