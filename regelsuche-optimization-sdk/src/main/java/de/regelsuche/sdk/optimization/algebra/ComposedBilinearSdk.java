package de.regelsuche.sdk.optimization.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearProgram;
import de.regelsuche.math.algorithms.algebra.ComposedBilinearPlan;
import de.regelsuche.math.algorithms.algebra.ComposedBilinearPrepared;
import de.regelsuche.math.algorithms.algebra.ComposedBilinearSearch;
import de.regelsuche.math.algorithms.algebra.ComposedBilinearVerifier;
import de.regelsuche.math.algorithms.algebra.OperatorResidualPlans;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;

/**
 * SDK entry points for fixed-width, checked bilinear procedure composition.
 * maximumCandidates is one shared count of top-level acquisition and declared stage-recipe slots.
 * Inner bilinear enumeration is governed by the same exact work, state and time limits.
 */
public final class ComposedBilinearSdk {
    private ComposedBilinearSdk() {}

    public static OperatorResidualPlans.Result<ComposedBilinearSearch.Discovery> search(BilinearProgram.Request request,
            BilinearProgram seed, int batchWidth, ComposedBilinearSearch.Policy policy,
            OptimizationBudget budget, CancellationToken cancel) {
        return ComposedBilinearSearch.search(request, seed, batchWidth, policy, budget.maximumCandidates(),
            limits(budget), cancel::isCancelled);
    }

    public static OperatorResidualPlans.Result<ComposedBilinearSearch.Discovery> discover(BilinearProgram.Tensor tensor,
            int maximumProducts, int batchWidth, ComposedBilinearSearch.Policy policy,
            OptimizationBudget budget, CancellationToken cancel) {
        return ComposedBilinearSearch.discover(tensor, maximumProducts, batchWidth, policy, budget.maximumCandidates(),
            limits(budget), cancel::isCancelled);
    }

    public static OperatorResidualPlans.Result<ComposedBilinearPlan.Cost> verify(BilinearProgram.Request request,
            int batchWidth, ComposedBilinearPlan plan, OptimizationBudget budget, CancellationToken cancel) {
        return ComposedBilinearVerifier.verify(request, batchWidth, plan, limits(budget), cancel::isCancelled);
    }

    public static OperatorResidualPlans.Result<ComposedBilinearPrepared> prepare(BilinearProgram.Request request,
            int batchWidth, ComposedBilinearPlan plan, OptimizationBudget budget, CancellationToken cancel) {
        return ComposedBilinearVerifier.prepare(request, batchWidth, plan, limits(budget), cancel::isCancelled);
    }

    public static OperatorResidualPlans.Result<List<List<ExactRational>>> apply(ComposedBilinearPrepared prepared,
            List<List<ExactRational>> left, List<List<ExactRational>> right,
            OptimizationBudget budget, CancellationToken cancel) {
        return prepared.apply(left, right, limits(budget), cancel::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
