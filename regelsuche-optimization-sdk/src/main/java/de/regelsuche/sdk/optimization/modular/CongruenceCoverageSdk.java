package de.regelsuche.sdk.optimization.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.CongruenceCoverage;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** SDK access to exact coverage of finite progressions by activated congruences. */
public final class CongruenceCoverageSdk {
    private CongruenceCoverageSdk() {}

    public static CongruenceCoverage.Result search(CongruenceCoverage.Request request,
            OptimizationBudget budget, CancellationToken cancellation) {
        return CongruenceCoverage.search(request, limits(budget), cancellation::isCancelled);
    }

    public static CongruenceCoverage.Verification verify(CongruenceCoverage.Request request,
            CongruenceCoverage.Certificate certificate, OptimizationBudget budget,
            CancellationToken cancellation) {
        return CongruenceCoverage.verify(request, certificate, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
