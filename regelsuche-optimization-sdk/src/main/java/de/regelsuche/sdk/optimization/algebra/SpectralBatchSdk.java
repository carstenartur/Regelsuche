package de.regelsuche.sdk.optimization.algebra;

import de.regelsuche.math.algorithms.algebra.PreparedSpectralBatch;
import de.regelsuche.math.algorithms.algebra.SpectralBatchRepair;
import de.regelsuche.math.algorithms.algebra.SpectralBatchRepairVerifier;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.scalar.ExactRational;
import java.util.List;

import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.*;

/**
 * Exact rational batch repair with the caller's shared work, state, candidate and
 * cancellation limits.
 */
public final class SpectralBatchSdk {
    private SpectralBatchSdk() {}

    public static Result<SpectralBatchRepair.Problem> deriveProblem(PolynomialMatrix source, int batchWidth,
            OptimizationBudget budget, CancellationToken cancel) {
        return SpectralBatchRepair.deriveProblem(source, batchWidth, limits(budget), cancel::isCancelled);
    }

    public static Result<SpectralBatchRepair.Certificate> construct(SpectralBatchRepair.Problem problem,
            List<ExactMatrixExpression> predictors, OptimizationBudget budget, CancellationToken cancel) {
        return SpectralBatchRepair.construct(problem, predictors, limits(budget), cancel::isCancelled);
    }

    public static Result<PreparedSpectralBatch> prepare(SpectralBatchRepair.Problem problem,
            SpectralBatchRepair.Certificate certificate, OptimizationBudget budget, CancellationToken cancel) {
        return SpectralBatchRepair.prepare(problem, certificate, limits(budget), cancel::isCancelled);
    }

    public static Result<SpectralBatchRepair.Discovery> discover(PolynomialMatrix source, int batchWidth,
            SpectralBatchRepair.Policy policy, OptimizationBudget budget, CancellationToken cancel) {
        return SpectralBatchRepair.discover(source, batchWidth, policy, budget.maximumCandidates(), limits(budget),
                cancel::isCancelled);
    }

    public static Result<SpectralBatchRepair.Discovery> discover(SpectralBatchRepair.Problem problem,
            SpectralBatchRepair.Policy policy, OptimizationBudget budget, CancellationToken cancel) {
        return SpectralBatchRepair.discover(problem, policy, budget.maximumCandidates(), limits(budget),
                cancel::isCancelled);
    }

    public static Result<Cost> verify(SpectralBatchRepair.Problem problem, SpectralBatchRepair.Certificate certificate,
            OptimizationBudget budget, CancellationToken cancel) {
        return SpectralBatchRepairVerifier.verify(problem, certificate, limits(budget), cancel::isCancelled);
    }

    public static Result<List<List<ExactRational>>> apply(PreparedSpectralBatch prepared, List<List<ExactRational>> input,
            OptimizationBudget budget, CancellationToken cancel) {
        return prepared.apply(input, limits(budget), cancel::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
