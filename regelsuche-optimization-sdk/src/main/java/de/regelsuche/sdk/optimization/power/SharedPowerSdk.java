package de.regelsuche.sdk.optimization.power;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.SharedPowerEvaluation;
import de.regelsuche.math.algorithms.modular.SharedPowerPlan;
import de.regelsuche.math.algorithms.modular.SharedPowerSearch;
import de.regelsuche.math.algorithms.modular.SharedPowerVerifier;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;

/** Typed access to shared powers; all proposal, proof and evaluation logic lives in the mathematical core. */
public final class SharedPowerSdk {
    private SharedPowerSdk() {}

    public static SharedPowerSearch.Result search(SharedPowerPlan.Request request, SharedPowerPlan.Weights weights,
            int maximumShift, OptimizationBudget budget, CancellationToken cancellation) {
        return SharedPowerSearch.search(request, weights, maximumShift, Math.min(6, budget.maximumCandidates()),
                limits(budget), cancellation::isCancelled);
    }

    public static SharedPowerVerifier.Verification verify(SharedPowerPlan.Request request,
            SharedPowerPlan.Certificate certificate, SharedPowerPlan.Weights weights,
            OptimizationBudget budget, CancellationToken cancellation) {
        return SharedPowerVerifier.verify(request, certificate, weights, limits(budget), cancellation::isCancelled);
    }

    public static SharedPowerEvaluation.Result evaluate(SharedPowerPlan.Request request,
            SharedPowerPlan.Certificate certificate, BigInteger modulus, BigInteger base,
            OptimizationBudget budget, CancellationToken cancellation) {
        return SharedPowerEvaluation.evaluate(request, certificate, modulus, base,
                limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
