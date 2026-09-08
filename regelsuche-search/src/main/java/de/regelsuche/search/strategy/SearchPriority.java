package de.regelsuche.search.strategy;

/** Shared bounded arithmetic for expression cost and path penalties. */
final class SearchPriority {
    private SearchPriority() {
    }

    static int bestFirst(SearchState state, SearchProblem problem) {
        int cost = problem.costModel() == null
            ? state.score().weightedTotal()
            : problem.costModel().cost(state.expression(), problem.canonicalizer(), state.score());
        return bestFirst(state, cost);
    }

    static int bestFirst(SearchState state, int cost) {
        return saturate((long) cost + state.depth() * 2L + state.expandedStepCount() * 5L
            + (state.improvement() <= 0 && state.depth() > 0 ? 4 : 0));
    }

    static int saturate(long value) {
        return (int) Math.clamp(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }
}
