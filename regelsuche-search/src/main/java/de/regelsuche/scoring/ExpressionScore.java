package de.regelsuche.scoring;

public record ExpressionScore(
    int stringLength,
    int astNodeCount,
    int operatorCount,
    int nestingDepth,
    int recognizedPatternBonus
) {
    public int weightedTotal() {
        return stringLength + astNodeCount + operatorCount + nestingDepth - recognizedPatternBonus;
    }

    public int improvementTo(ExpressionScore other) {
        return weightedTotal() - other.weightedTotal();
    }
}
