package de.regelsuche.validation;

public record RewriteRuleValidationResult(
    String sourceExpression,
    String targetExpression,
    String ruleId,
    RewriteValidationStatus status,
    String evidence
) {
}
