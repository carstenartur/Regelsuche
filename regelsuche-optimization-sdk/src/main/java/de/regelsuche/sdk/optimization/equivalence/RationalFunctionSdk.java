package de.regelsuche.sdk.optimization.equivalence;

import de.regelsuche.math.algorithms.equivalence.RationalFunctionPlanner;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;

/** Exact rational proposals and independent source/domain verification, with caller-owned budgets. */
public final class RationalFunctionSdk {
    private RationalFunctionSdk() {}

    public static RationalFunctionPlanner.Result simplify(String source, List<String> assumptions,
            OptimizationBudget budget, CancellationToken cancellation) {
        return RationalFunctionPlanner.simplify(source, assumptions, limits(budget), cancellation::isCancelled);
    }

    public static RationalFunctionPlanner.Verification verify(String expectedSource,
            RationalFunctionPlanner.Certificate certificate, OptimizationBudget budget, CancellationToken cancellation) {
        return RationalFunctionPlanner.verify(expectedSource, certificate, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
