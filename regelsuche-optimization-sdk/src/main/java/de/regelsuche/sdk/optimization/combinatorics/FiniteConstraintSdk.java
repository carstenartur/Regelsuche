package de.regelsuche.sdk.optimization.combinatorics;

import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintVerifier;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** Typed access to bounded exact nonnegative weighted Boolean model counting. */
public final class FiniteConstraintSdk {
    private FiniteConstraintSdk() {}

    public static FiniteConstraintCounter.Result count(FiniteConstraintCounter.Problem problem,
            OptimizationBudget budget, CancellationToken cancellation) {
        return FiniteConstraintCounter.count(problem, limits(budget), cancellation::isCancelled);
    }

    public static FiniteConstraintVerifier.Verification verify(FiniteConstraintCounter.Problem problem,
            FiniteConstraintCounter.Certificate certificate, OptimizationBudget budget, CancellationToken cancellation) {
        return FiniteConstraintVerifier.verify(problem, certificate, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
