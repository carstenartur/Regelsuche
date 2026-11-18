package de.regelsuche.sdk.optimization.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.PolynomialCongruence;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** SDK access to complete, bounded preimages of integer polynomials modulo a positive integer. */
public final class PolynomialCongruenceSdk {
    private PolynomialCongruenceSdk() {}

    public static PolynomialCongruence.Result solve(PolynomialCongruence.Request request,
            OptimizationBudget budget, CancellationToken cancellation) {
        return PolynomialCongruence.solve(request, limits(budget), cancellation::isCancelled);
    }

    public static PolynomialCongruence.Verification verify(PolynomialCongruence.Request request,
            PolynomialCongruence.Solution solution, OptimizationBudget budget, CancellationToken cancellation) {
        return PolynomialCongruence.verify(request, solution, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
