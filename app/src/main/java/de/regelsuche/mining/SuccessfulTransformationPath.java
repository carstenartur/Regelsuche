package de.regelsuche.mining;

import de.regelsuche.scoring.ExpressionScore;
import java.util.List;
import java.util.Map;

public record SuccessfulTransformationPath(
    String id,
    String originalExpression,
    String targetExpression,
    List<String> expressionPath,
    List<String> rules,
    ExpressionScore scoreBefore,
    ExpressionScore scoreAfter,
    boolean equivalenceVerified,
    String equivalenceEvidence,
    Map<String, String> variableStructure
) {
    public SuccessfulTransformationPath {
        id = id == null || id.isBlank() ? "path-" + Integer.toHexString(System.identityHashCode(expressionPath)) : id;
        expressionPath = List.copyOf(expressionPath);
        rules = List.copyOf(rules);
        variableStructure = Map.copyOf(variableStructure);
    }

    public SuccessfulTransformationPath(
        String originalExpression,
        String targetExpression,
        List<String> expressionPath,
        List<String> rules,
        ExpressionScore scoreBefore,
        ExpressionScore scoreAfter,
        boolean equivalenceVerified,
        String equivalenceEvidence,
        Map<String, String> variableStructure
    ) {
        this(
            null,
            originalExpression,
            targetExpression,
            expressionPath,
            rules,
            scoreBefore,
            scoreAfter,
            equivalenceVerified,
            equivalenceEvidence,
            variableStructure
        );
    }

    public SuccessfulTransformationPath(
        String originalExpression,
        String targetExpression,
        List<String> expressionPath,
        List<String> rules,
        ExpressionScore scoreBefore,
        ExpressionScore scoreAfter,
        String equivalenceEvidence,
        Map<String, String> variableStructure
    ) {
        this(
            null,
            originalExpression,
            targetExpression,
            expressionPath,
            rules,
            scoreBefore,
            scoreAfter,
            true,
            equivalenceEvidence,
            variableStructure
        );
    }

    public int scoreImprovement() {
        return scoreBefore.improvementTo(scoreAfter);
    }
}
