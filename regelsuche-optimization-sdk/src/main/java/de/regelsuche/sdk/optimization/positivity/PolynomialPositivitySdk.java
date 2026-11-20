package de.regelsuche.sdk.optimization.positivity;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.positivity.BernsteinPositivity;
import de.regelsuche.math.algorithms.positivity.GramPositivity;
import de.regelsuche.math.algorithms.positivity.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;

/** Typed access to exact Bernstein enclosures, box positivity proofs and rational Gram verification. */
public final class PolynomialPositivitySdk {
    private PolynomialPositivitySdk() {}

    public static BernsteinPositivity.BoundsResult bounds(BernsteinPositivity.Request request,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BernsteinPositivity.bounds(request, limits(budget), cancellation::isCancelled);
    }

    public static BernsteinPositivity.Verification verifyBounds(BernsteinPositivity.Request request,
            BernsteinPositivity.Bounds bounds, OptimizationBudget budget, CancellationToken cancellation) {
        return BernsteinPositivity.verifyBounds(request, bounds, limits(budget), cancellation::isCancelled);
    }

    public static BernsteinPositivity.Result prove(BernsteinPositivity.Request request, int maxDepth,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BernsteinPositivity.prove(request, maxDepth, limits(budget), cancellation::isCancelled);
    }

    public static BernsteinPositivity.Verification verify(BernsteinPositivity.Request request,
            BernsteinPositivity.Certificate certificate, OptimizationBudget budget, CancellationToken cancellation) {
        return BernsteinPositivity.verify(request, certificate, limits(budget), cancellation::isCancelled);
    }

    public static GramPositivity.MatrixResult checkMatrix(List<List<ExactRational>> matrix,
            OptimizationBudget budget, CancellationToken cancellation) {
        return GramPositivity.checkMatrix(matrix, limits(budget), cancellation::isCancelled);
    }

    public static GramPositivity.PolynomialVerification verifyPolynomial(SparsePolynomial source,
            GramPositivity.Certificate certificate, OptimizationBudget budget, CancellationToken cancellation) {
        return GramPositivity.verifyPolynomial(source, certificate, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
