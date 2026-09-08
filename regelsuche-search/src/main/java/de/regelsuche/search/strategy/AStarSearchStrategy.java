package de.regelsuche.search.strategy;

public class AStarSearchStrategy extends BestFirstSearchStrategy {
    @Override
    protected int priority(SearchState state) {
        return pathPriority(state, state.score().weightedTotal());
    }

    @Override
    protected int priority(SearchState state, SearchProblem problem) {
        return problem.costModel() == null ? priority(state) : pathPriority(state,
            problem.costModel().cost(state.expression(), problem.canonicalizer(), state.score()));
    }

    private static int pathPriority(SearchState state, int modelCost) {
        long costSoFar = state.depth() * 3L + state.expandedStepCount() * 4L;
        long estimatedRemainingCost = Math.max(0, (long) modelCost - state.score().recognizedPatternBonus());
        int diversityBonus = Math.min(6, state.appliedRuleIds().stream().distinct().toList().size());
        return SearchPriority.saturate(costSoFar + estimatedRemainingCost - diversityBonus);
    }
}
