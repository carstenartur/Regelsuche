package de.regelsuche.sdk.optimization.combinatorics;

import de.regelsuche.math.algorithms.combinatorics.ConflictMatching;
import de.regelsuche.math.algorithms.combinatorics.ConflictMatchingVerifier;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** Thin SDK entry points for general matching bounds with independently justified edge conflicts. */
public final class ConflictMatchingSdk {
    private ConflictMatchingSdk() {}

    public static ConflictMatching.Result search(ConflictMatching.Request request,
            OptimizationBudget budget, CancellationToken cancellation) {
        return ConflictMatching.search(request, limits(budget), cancellation::isCancelled);
    }

    public static ConflictMatchingVerifier.Verification verify(ConflictMatching.Request request,
            ConflictMatching.Certificate certificate, OptimizationBudget budget, CancellationToken cancellation) {
        return ConflictMatchingVerifier.verify(request, certificate, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
