package de.regelsuche.search;

public record SearchHeuristic(
    int maxDepth,
    int maxVisitedExpressions,
    int significantImprovementThreshold,
    int maxExpandingSteps,
    int maxCandidatesPerState,
    int beamWidth
) {
    public SearchHeuristic(int maxDepth, int maxVisitedExpressions, int significantImprovementThreshold) {
        this(maxDepth, maxVisitedExpressions, significantImprovementThreshold, 4, 80, 12);
    }

    public SearchHeuristic {
        if (maxDepth < 0 || maxVisitedExpressions < 1 || significantImprovementThreshold < 1
            || maxExpandingSteps < 0 || maxCandidatesPerState < 1 || beamWidth < 1) {
            throw new IllegalArgumentException("heuristic values must be positive");
        }
    }

    public boolean withinLimits(int depth, int visitedCount) {
        return depth <= maxDepth && visitedCount < maxVisitedExpressions;
    }

    public boolean shouldNotify(int oldComplexity, int newComplexity) {
        return oldComplexity - newComplexity >= significantImprovementThreshold;
    }
}
