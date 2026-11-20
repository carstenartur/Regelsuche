package de.regelsuche.sdk.optimization.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.CongruenceCoverage;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** SDK access to exact coverage and survivor bounds for finite progressions. */
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

    public static CongruenceCoverage.BoundsResult bounds(CongruenceCoverage.Request request, int order,
            OptimizationBudget budget, CancellationToken cancellation) {
        return CongruenceCoverage.bounds(request, order, limits(budget), cancellation::isCancelled);
    }

    public static CongruenceCoverage.Verification verifyBounds(CongruenceCoverage.Request request, int order,
            CongruenceCoverage.BoundsCertificate certificate, OptimizationBudget budget,
            CancellationToken cancellation) {
        return CongruenceCoverage.verifyBounds(request, order, certificate, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
