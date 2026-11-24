package de.regelsuche.sdk.optimization.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearFormDiscovery;
import de.regelsuche.math.algorithms.algebra.BilinearProductLearning;
import de.regelsuche.math.algorithms.algebra.BilinearProgram;
import de.regelsuche.math.algorithms.algebra.BilinearRankBound;
import de.regelsuche.math.algorithms.algebra.BilinearProgramSearch;
import de.regelsuche.math.algorithms.algebra.BilinearProgramVerifier;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;

/** SDK entry points for bounded rational tensor-program search, checking and execution. */
public final class BilinearProgramSdk {
    private BilinearProgramSdk() {}

    public static BilinearProgram.Result<BilinearProductLearning.Basis> trainProducts(String sourceId,
            BilinearProgram.Tensor tensor, int maximumProducts, OptimizationBudget budget,
            CancellationToken cancellation) {
        return BilinearProductLearning.train(sourceId, tensor, maximumProducts, limits(budget),
            cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearProductLearning.Application> reuseProducts(
            BilinearProductLearning.Basis basis, BilinearProgram.Tensor tensor, int maximumProducts,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearProductLearning.apply(basis, tensor, maximumProducts, limits(budget),
            cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearFormDiscovery.Discovery> discover(BilinearProgram.Tensor tensor,
            int maximumProducts, OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearFormDiscovery.discover(tensor, maximumProducts, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearRankBound.Certificate> computeRankBound(BilinearProgram.Tensor tensor,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearRankBound.compute(tensor, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<Integer> verifyRankBound(BilinearProgram.Tensor tensor,
            BilinearRankBound.Certificate certificate, OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearRankBound.verify(tensor, certificate, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearRankBound.Interval> certifyProductBounds(
            BilinearProgram.Request request, BilinearProgram program, BilinearRankBound.Certificate lowerBound,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearRankBound.certify(request, program, lowerBound, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearProgram.Witness> search(BilinearProgram.Request request,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearProgramSearch.search(request, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<BilinearProgram.Cost> verify(BilinearProgram.Request request,
            BilinearProgram program, OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearProgramVerifier.verify(request, program, limits(budget), cancellation::isCancelled);
    }

    public static BilinearProgram.Result<List<ExactRational>> evaluate(BilinearProgram.Request request,
            BilinearProgram program, List<ExactRational> x, List<ExactRational> y,
            OptimizationBudget budget, CancellationToken cancellation) {
        return BilinearProgramVerifier.evaluate(request, program, x, y, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
