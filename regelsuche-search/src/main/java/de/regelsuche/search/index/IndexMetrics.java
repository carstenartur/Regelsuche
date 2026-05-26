package de.regelsuche.search.index;

/** Detailed accounting for the multi-stage candidate narrowing pipeline. */
public record IndexMetrics(
    int rulesConsidered,
    int rulesSkippedByRoot,
    int rulesSkippedBySignature,
    int rulesSkippedByFeatureVector,
    int rulesSkippedByDiscriminationTree,
    int rulesSkippedByGoalAwareRanking,
    int rulesSkippedByBudget,
    int rulesMatched,
    double averageCandidateSetSize
) {
    public static IndexMetrics empty() {
        return new IndexMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0.0);
    }

    public int rulesSkippedByIndex() {
        return rulesSkippedByRoot + rulesSkippedBySignature + rulesSkippedByFeatureVector
            + rulesSkippedByDiscriminationTree + rulesSkippedByGoalAwareRanking + rulesSkippedByBudget;
    }

    public TermRuleIndex.Metrics asTermRuleMetrics() {
        return new TermRuleIndex.Metrics(rulesConsidered, rulesSkippedByIndex(), rulesMatched);
    }
}
