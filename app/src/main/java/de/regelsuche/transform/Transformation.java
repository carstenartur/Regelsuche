package de.regelsuche.transform;

public record Transformation(String rule, String transformedExpression) {
    public Transformation {
        if (rule == null || rule.isBlank() || transformedExpression == null || transformedExpression.isBlank()) {
            throw new IllegalArgumentException("rule and transformedExpression must not be blank");
        }
    }
}
