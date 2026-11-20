package de.regelsuche.sdk.optimization.linalg;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ModularKernel;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** SDK access to independently verified homogeneous rational substitutions and complete kernels. */
public final class ModularKernelSdk {
    private ModularKernelSdk() {}

    public static ModularKernel.Result search(ExactMatrix source, ModularKernel.Strength strength,
            OptimizationBudget budget, CancellationToken cancellation) {
        return ModularKernel.search(source, strength, limits(budget), cancellation::isCancelled);
    }

    public static boolean verify(ExactMatrix source, ModularKernel.Strength strength,
            ModularKernel.Certificate certificate, OptimizationBudget budget, CancellationToken cancellation) {
        return ModularKernel.verify(source, strength, certificate, limits(budget), cancellation::isCancelled);
    }

    private static ModularKernel.Limits limits(OptimizationBudget budget) {
        return ModularKernel.Limits.defaults(new IterationLimits(budget.maximumWork(), budget.maximumStates(),
            budget.timeoutMillis()));
    }
}
