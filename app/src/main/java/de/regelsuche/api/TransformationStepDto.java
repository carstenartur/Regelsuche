package de.regelsuche.api;

import de.regelsuche.discovery.TransformationStep;
import java.util.List;

public record TransformationStepDto(
    int index,
    String beforeExpression,
    String afterExpression,
    String ruleId,
    String ruleKind,
    int scoreBefore,
    int scoreAfter,
    boolean equivalencePreserving,
    String explanation,
    List<String> assumptions
) {
    public TransformationStepDto(
        int index,
        String beforeExpression,
        String afterExpression,
        String ruleId,
        String ruleKind,
        int scoreBefore,
        int scoreAfter,
        boolean equivalencePreserving,
        String explanation
    ) {
        this(index, beforeExpression, afterExpression, ruleId, ruleKind, scoreBefore, scoreAfter,
            equivalencePreserving, explanation, List.of());
    }

    public static TransformationStepDto from(TransformationStep step) {
        return new TransformationStepDto(
            step.index(),
            step.beforeExpression(),
            step.afterExpression(),
            step.ruleId(),
            step.ruleKind().name(),
            step.scoreBefore(),
            step.scoreAfter(),
            step.equivalencePreserving(),
            step.explanation(),
            step.assumptions()
        );
    }
}
