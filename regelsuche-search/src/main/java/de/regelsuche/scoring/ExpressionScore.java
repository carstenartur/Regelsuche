package de.regelsuche.scoring;

public record ExpressionScore(
    int stringLength,
    int astNodeCount,
    int operatorCount,
    int nestingDepth,
    int recognizedPatternBonus,
    String scoringRevision
) {
    public ExpressionScore {
        scoringRevision = ScoreRevision.normalize(scoringRevision);
    }

    /** Raw numeric values do not identify their producer, including historical/custom scores. */
    public ExpressionScore(int stringLength, int astNodeCount, int operatorCount,
            int nestingDepth, int recognizedPatternBonus) {
        this(stringLength, astNodeCount, operatorCount, nestingDepth, recognizedPatternBonus, ScoreRevision.UNSPECIFIED);
    }

    public int weightedTotal() {
        return stringLength + astNodeCount + operatorCount + nestingDepth - recognizedPatternBonus;
    }

    public int improvementTo(ExpressionScore other) {
        if (!scoringRevision.equals(other.scoringRevision)) {
            throw new IllegalArgumentException("cannot compare different scoring revisions");
        }
        return weightedTotal() - other.weightedTotal();
    }
}
