package de.regelsuche.sdk.optimization.power;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.QuadraticRootContract;
import de.regelsuche.math.algorithms.modular.QuadraticRootEvaluation;
import de.regelsuche.math.algorithms.modular.QuadraticRootSearch;
import de.regelsuche.math.algorithms.modular.QuadraticRootVerifier;
import de.regelsuche.math.algorithms.modular.SharedPowerPlan;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;

/** Budget adapter for source-bound quadratic-root programs. */
public final class QuadraticRootSdk {
    private QuadraticRootSdk() {}

    public static QuadraticRootSearch.Result search(QuadraticRootContract expected, SharedPowerPlan.Weights weights,
            OptimizationBudget budget, CancellationToken cancellation) {
        return QuadraticRootSearch.search(expected, weights, budget.maximumCandidates(), limits(budget), cancellation::isCancelled);
    }

    public static QuadraticRootVerifier.Result verify(QuadraticRootContract expected, QuadraticRootSearch.Certificate certificate,
            OptimizationBudget budget, CancellationToken cancellation) {
        return QuadraticRootVerifier.verify(expected, certificate, limits(budget), cancellation::isCancelled);
    }

    public static QuadraticRootEvaluation.Result evaluate(QuadraticRootContract expected, QuadraticRootSearch.Certificate certificate,
            BigInteger a, BigInteger b, BigInteger g, OptimizationBudget budget, CancellationToken cancellation) {
        return QuadraticRootEvaluation.evaluate(expected, certificate, a, b, g, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
