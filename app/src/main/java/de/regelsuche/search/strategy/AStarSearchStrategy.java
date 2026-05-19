package de.regelsuche.search.strategy;

public class AStarSearchStrategy extends BestFirstSearchStrategy {
    @Override
    protected int priority(SearchState state) {
        int costSoFar = state.depth() * 3 + state.expandedStepCount() * 4;
        int estimatedRemainingCost = Math.max(0, state.score().weightedTotal() - state.score().recognizedPatternBonus());
        int diversityBonus = Math.min(6, state.appliedRuleIds().stream().distinct().toList().size());
        return costSoFar + estimatedRemainingCost - diversityBonus;
    }
}
