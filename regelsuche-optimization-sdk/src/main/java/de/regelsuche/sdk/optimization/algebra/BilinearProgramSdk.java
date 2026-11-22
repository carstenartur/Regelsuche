package de.regelsuche.sdk.optimization.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearProgram;
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
