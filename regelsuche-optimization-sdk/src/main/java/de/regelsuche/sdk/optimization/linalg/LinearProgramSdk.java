package de.regelsuche.sdk.optimization.linalg;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.FarkasSparsifier;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Certificate;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Infeasible;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Problem;
import de.regelsuche.math.algorithms.linalg.LinearProgramVerifier;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** SDK entry points for exact linear programming certificates and Farkas support reduction. */
public final class LinearProgramSdk {
    private LinearProgramSdk() {}

    public static LinearProgramVerifier.Verification verify(Problem problem, Certificate certificate,
            OptimizationBudget budget, CancellationToken cancellation) {
        return LinearProgramVerifier.verify(problem, certificate, limits(budget), cancellation::isCancelled);
    }

    public static FarkasSparsifier.Result sparsify(Problem problem, Infeasible certificate,
            OptimizationBudget budget, CancellationToken cancellation) {
        return FarkasSparsifier.sparsify(problem, certificate, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
