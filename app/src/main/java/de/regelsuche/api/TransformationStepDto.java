package de.regelsuche.api;

import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.transform.RecordedExecution;
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
    List<String> assumptions,
    RecordedExecution execution
) {
    public TransformationStepDto {
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        if (execution != null) execution.requireStep(beforeExpression, afterExpression, ruleId,
            de.regelsuche.transform.RewriteKind.valueOf(ruleKind), equivalencePreserving, assumptions);
    }

    public TransformationStepDto(int index, String beforeExpression, String afterExpression, String ruleId,
            String ruleKind, int scoreBefore, int scoreAfter, boolean equivalencePreserving,
            String explanation, List<String> assumptions) {
        this(index, beforeExpression, afterExpression, ruleId, ruleKind, scoreBefore, scoreAfter,
            equivalencePreserving, explanation, assumptions, null);
    }
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
            step.assumptions(),
            step.execution()
        );
    }
}
