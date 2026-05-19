package de.regelsuche.mining;

import de.regelsuche.scoring.ExpressionScore;
import java.util.List;
import java.util.Map;

public record SuccessfulTransformationPath(
    String originalExpression,
    String targetExpression,
    List<String> expressionPath,
    List<String> rules,
    ExpressionScore scoreBefore,
    ExpressionScore scoreAfter,
    String equivalenceEvidence,
    Map<String, String> variableStructure
) {
    public SuccessfulTransformationPath {
        expressionPath = List.copyOf(expressionPath);
        rules = List.copyOf(rules);
        variableStructure = Map.copyOf(variableStructure);
    }

    public int scoreImprovement() {
        return scoreBefore.improvementTo(scoreAfter);
    }
}
