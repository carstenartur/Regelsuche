package de.regelsuche.sdk.optimization.linalg;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.AffineImplication;
import de.regelsuche.math.algorithms.linalg.AffineImplicationVerifier;
import de.regelsuche.math.algorithms.linalg.FarkasSparsifier;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Certificate;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Infeasible;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Problem;
import de.regelsuche.math.algorithms.linalg.LinearProgramSearch;
import de.regelsuche.math.algorithms.linalg.LinearProgramVerifier;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** SDK entry points for exact LP search, affine implication and checked certificates. */
public final class LinearProgramSdk {
    private LinearProgramSdk() {}

    public static LinearProgramSearch.Result search(Problem problem,
            OptimizationBudget budget, CancellationToken cancellation) {
        return LinearProgramSearch.search(problem, limits(budget), cancellation::isCancelled);
    }

    public static AffineImplication.Result imply(AffineImplication.Request request,
            OptimizationBudget budget, CancellationToken cancellation) {
        return AffineImplication.search(request, limits(budget), cancellation::isCancelled);
    }

    public static LinearProgramVerifier.Verification verifyImplication(AffineImplication.Request request,
            AffineImplication.Evidence evidence, OptimizationBudget budget, CancellationToken cancellation) {
        return AffineImplicationVerifier.verify(request, evidence, limits(budget), cancellation::isCancelled);
    }

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
