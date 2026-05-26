package de.regelsuche.api.searchgraph.semantic;

public record MainPathCriteria(
    double complexityReductionWeight,
    double proofConfidenceWeight,
    double macroCompressionWeight,
    double lowSignalPenalty,
    double lengthPenalty,
    double assumptionPenalty,
    double teachingScoreWeight
) {
    public static MainPathCriteria defaults() {
        return new MainPathCriteria(1.5, 1.0, 0.8, 0.8, 0.3, 0.7, 0.4);
    }
}
