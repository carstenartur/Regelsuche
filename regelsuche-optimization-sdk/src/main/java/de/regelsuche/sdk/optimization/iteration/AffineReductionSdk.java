package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.iteration.AffineReduction;
import de.regelsuche.math.algorithms.iteration.AffineReductionPlan;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;

/** Source-bound exact aggregation over affine integer domains. */
public final class AffineReductionSdk {
    private AffineReductionSdk() {}

    public static AffineReductionPlan.Preparation prepare(AffineReduction source, OptimizationBudget budget,
            CancellationToken cancellation) {
        return AffineReductionPlan.prepare(source, limits(budget), cancellation::isCancelled);
    }

    public static AffineReductionPlan.Evaluation evaluate(AffineReductionPlan.Verified plan, int parameter,
            List<BigInteger> input, OptimizationBudget budget, CancellationToken cancellation) {
        return plan.evaluate(parameter, input, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
