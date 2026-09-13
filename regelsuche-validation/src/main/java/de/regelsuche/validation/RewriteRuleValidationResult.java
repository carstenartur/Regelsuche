package de.regelsuche.validation;

import java.util.List;

/** A validation decision scoped to every retained side condition. */
public record RewriteRuleValidationResult(
    String sourceExpression,
    String targetExpression,
    String ruleId,
    RewriteValidationStatus status,
    String evidence,
    List<String> assumptions
) {
    public RewriteRuleValidationResult {
        assumptions = List.copyOf(assumptions);
    }

    public RewriteRuleValidationResult(String sourceExpression, String targetExpression,
        String ruleId, RewriteValidationStatus status, String evidence) {
        this(sourceExpression, targetExpression, ruleId, status, evidence, List.of());
    }
}
