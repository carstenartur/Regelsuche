package de.regelsuche.sdk.optimization.combinatorics;

import de.regelsuche.math.algorithms.combinatorics.EnergyGame;
import de.regelsuche.math.algorithms.combinatorics.EnergyGameVerifier;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** Typed access to bounded cyclic energy-credit certificates and independent verification. */
public final class EnergyGameSdk {
    private EnergyGameSdk() {}

    public static EnergyGame.Result solve(EnergyGame.Request request, OptimizationBudget budget, CancellationToken cancellation) {
        return EnergyGame.solve(request, limits(budget), cancellation::isCancelled);
    }

    public static EnergyGameVerifier.Verification verify(EnergyGame.Request request, EnergyGame.Certificate certificate,
            OptimizationBudget budget, CancellationToken cancellation) {
        return EnergyGameVerifier.verify(request, certificate, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
