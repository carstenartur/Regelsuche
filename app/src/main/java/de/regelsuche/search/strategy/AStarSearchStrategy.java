package de.regelsuche.search.strategy;

public class AStarSearchStrategy extends BestFirstSearchStrategy {
    @Override
    protected int priority(SearchState state) {
        int costSoFar = state.depth() * 3 + state.expandedStepCount() * 4;
        int estimatedRemainingCost = Math.max(0, state.score().weightedTotal() - state.score().recognizedPatternBonus());
        int diversityBonus = Math.min(6, state.appliedRuleIds().stream().distinct().toList().size());
        return costSoFar + estimatedRemainingCost - diversityBonus;
    }

    @Override
    protected int priority(SearchState state, SearchProblem problem) {
        if (problem.costModel() == null) {
            return priority(state);
        }
        int costSoFar = state.depth() * 3 + state.expandedStepCount() * 4;
        int modelCost = problem.costModel().cost(state.expression(), problem.canonicalizer(), state.score());
        if (modelCost == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE / 2;
        }
        int estimatedRemainingCost = Math.max(0, modelCost - state.score().recognizedPatternBonus());
        int diversityBonus = Math.min(6, state.appliedRuleIds().stream().distinct().toList().size());
        return costSoFar + estimatedRemainingCost - diversityBonus;
    }
}
