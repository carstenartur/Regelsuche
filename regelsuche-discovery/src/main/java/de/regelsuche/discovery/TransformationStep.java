package de.regelsuche.discovery;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.transform.RewriteKind;
import java.util.List;

public record TransformationStep(
    int index,
    String beforeExpression,
    String afterExpression,
    String ruleId,
    RewriteKind ruleKind,
    int scoreBefore,
    int scoreAfter,
    boolean equivalencePreserving,
    String explanation,
    List<String> assumptions
) {
    public TransformationStep {
        if (beforeExpression == null || afterExpression == null || ruleId == null || ruleKind == null) {
            throw new IllegalArgumentException("step expressions, ruleId and ruleKind are required");
        }
        explanation = explanation == null ? "" : explanation;
        assumptions = AssumptionSignature.ofExpressions(assumptions).normalizedAssumptions();
    }

    public TransformationStep(
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
        this(index, beforeExpression, afterExpression, ruleId, ruleKind, scoreBefore, scoreAfter,
            equivalencePreserving, explanation, List.of());
    }
}
