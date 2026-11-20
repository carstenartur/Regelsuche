package de.regelsuche.sdk.optimization.combinatorics;

import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** SDK access to certified maximum matching in finite bipartite graphs. */
public final class BipartiteMatchingSdk {
    private BipartiteMatchingSdk() {}

    public static BipartiteMatching.Result search(BipartiteMatching.Request request,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BipartiteMatching.search(request, limits(budget), cancellation::isCancelled);
    }

    public static BipartiteMatching.Verification verify(BipartiteMatching.Request request,
            BipartiteMatching.Certificate certificate, OptimizationBudget budget, CancellationToken cancellation) {
        return BipartiteMatching.verify(request, certificate, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
