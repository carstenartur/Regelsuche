package de.regelsuche.sdk.optimization.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.CongruenceCoverage;
import de.regelsuche.math.algorithms.modular.HunterCoverage;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** SDK access to a finite Hunter lower bound and independent tree replay. */
public final class HunterCoverageSdk {
    private HunterCoverageSdk() {}

    public static HunterCoverage.Result bounds(CongruenceCoverage.Request request,
            OptimizationBudget budget, CancellationToken cancellation) {
        return HunterCoverage.bounds(request, limits(budget), cancellation::isCancelled);
    }

    public static CongruenceCoverage.Verification verify(CongruenceCoverage.Request request,
            HunterCoverage.Certificate certificate, OptimizationBudget budget, CancellationToken cancellation) {
        return HunterCoverage.verify(request, certificate, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
