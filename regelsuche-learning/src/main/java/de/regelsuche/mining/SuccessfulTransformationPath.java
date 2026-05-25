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
        id = id == null || id.isBlank() ? deterministicId(originalExpression, targetExpression, expressionPath, rules) : id;
        expressionPath = List.copyOf(expressionPath);
        rules = List.copyOf(rules);
        variableStructure = Map.copyOf(variableStructure);
    }

    private static String deterministicId(
        String originalExpression,
        String targetExpression,
        List<String> expressionPath,
        List<String> rules
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append(originalExpression == null ? "" : originalExpression).append('\u0001');
        builder.append(targetExpression == null ? "" : targetExpression).append('\u0001');
        if (expressionPath != null) {
            for (String step : expressionPath) {
                builder.append(step).append('\u0002');
            }
        }
        builder.append('\u0001');
        if (rules != null) {
            for (String rule : rules) {
                builder.append(rule).append('\u0003');
            }
        }
        return "path-" + Long.toHexString(Integer.toUnsignedLong(builder.toString().hashCode()));
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
