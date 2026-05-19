package de.regelsuche.mining;

public record RuleCandidate(
    String leftPattern,
    String rightPattern,
    int examplesCount,
    double averageScoreImprovement,
    int maximumScoreImprovement,
    boolean equivalenceVerified,
    boolean generalizationPlausible,
    boolean containsFreeParameters,
    RuleStatus status,
    String canonicalHash
) {
}
