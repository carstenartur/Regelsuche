package de.regelsuche.sdk.optimization.positivity;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.positivity.PolynomialPotential;
import de.regelsuche.math.algorithms.positivity.SosSynthesis;
import de.regelsuche.math.algorithms.positivity.SosVerifier;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** Typed search and replay for rational inequality and polynomial potential certificates. */
public final class SosSynthesisSdk {
    private SosSynthesisSdk() {}

    public static SosSynthesis.Result search(SosSynthesis.Request request, OptimizationBudget budget,
            CancellationToken cancellation) {
        return SosSynthesis.search(request, limits(budget), cancellation::isCancelled);
    }

    public static SosSynthesis.Verification verify(SosSynthesis.Request request, SosSynthesis.Certificate proof,
            OptimizationBudget budget, CancellationToken cancellation) {
        return SosVerifier.verify(request, proof, limits(budget), cancellation::isCancelled);
    }

    public static PolynomialPotential.Result search(PolynomialPotential.Request request, OptimizationBudget budget,
            CancellationToken cancellation) {
        return PolynomialPotential.search(request, limits(budget), cancellation::isCancelled);
    }

    public static PolynomialPotential.Verification verify(PolynomialPotential.Request request,
            PolynomialPotential.Certificate proof, OptimizationBudget budget, CancellationToken cancellation) {
        return PolynomialPotential.verify(request, proof, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
