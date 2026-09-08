package de.regelsuche.search.strategy;

public class AStarSearchStrategy extends BestFirstSearchStrategy {
    @Override
    protected int priority(SearchState state) {
        long costSoFar = state.depth() * 3L + state.expandedStepCount() * 4L;
        long estimatedRemainingCost = Math.max(0, (long) state.score().weightedTotal() - state.score().recognizedPatternBonus());
        int diversityBonus = Math.min(6, state.appliedRuleIds().stream().distinct().toList().size());
        return SearchPriority.saturate(costSoFar + estimatedRemainingCost - diversityBonus);
    }

    @Override
    protected int priority(SearchState state, SearchProblem problem) {
        if (problem.costModel() == null) {
            return priority(state);
        }
        long costSoFar = state.depth() * 3L + state.expandedStepCount() * 4L;
        int modelCost = problem.costModel().cost(state.expression(), problem.canonicalizer(), state.score());
        long estimatedRemainingCost = Math.max(0, (long) modelCost - state.score().recognizedPatternBonus());
        int diversityBonus = Math.min(6, state.appliedRuleIds().stream().distinct().toList().size());
        return SearchPriority.saturate(costSoFar + estimatedRemainingCost - diversityBonus);
    }
}
