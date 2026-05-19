package de.regelsuche.discovery;

import de.regelsuche.transform.RewriteKind;

public record TransformationStep(
    int index,
    String beforeExpression,
    String afterExpression,
    String ruleId,
    RewriteKind ruleKind,
    int scoreBefore,
    int scoreAfter,
    boolean equivalencePreserving,
    String explanation
) {
    public TransformationStep {
        if (beforeExpression == null || afterExpression == null || ruleId == null || ruleKind == null) {
            throw new IllegalArgumentException("step expressions, ruleId and ruleKind are required");
        }
        explanation = explanation == null ? "" : explanation;
    }
}
