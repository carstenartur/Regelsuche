package de.regelsuche.search;

public record SearchHeuristic(int maxDepth, int maxVisitedExpressions, int significantImprovementThreshold) {
    public SearchHeuristic {
        if (maxDepth < 0 || maxVisitedExpressions < 1 || significantImprovementThreshold < 1) {
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
