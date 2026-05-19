package de.regelsuche.mining;

import java.util.List;

public record RuleCandidate(
    String leftPattern,
    String rightPattern,
    int examplesCount,
    double averageScoreImprovement,
    int maximumScoreImprovement,
    boolean equivalenceVerified,
    boolean generalizationPlausible,
    boolean containsFreeParameters,
    List<String> parameterRelations,
    RuleStatus status,
    String canonicalHash
) {
    public RuleCandidate {
        parameterRelations = List.copyOf(parameterRelations);
    }
}
