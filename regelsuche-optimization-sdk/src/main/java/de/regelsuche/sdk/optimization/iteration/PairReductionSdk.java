package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.iteration.PairReductionPlan;
import de.regelsuche.math.algorithms.iteration.PairReductionSearch;
import de.regelsuche.math.algorithms.iteration.PairReductionVerifier;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;

/** SDK access to Regelsuche's checked pair search and exact evaluation. */
public final class PairReductionSdk {
    private PairReductionSdk() {}

    public static PairReductionSearch.Result search(PairReduction source, OptimizationBudget budget,
            CancellationToken cancellation) {
        return PairReductionSearch.search(source, limits(budget), budget.maximumStates(), cancellation::isCancelled);
    }

    public static PairReductionVerifier.Result verify(PairReduction source, PairReductionPlan plan,
            OptimizationBudget budget, CancellationToken cancellation) {
        return new PairReductionVerifier().verify(source, plan, limits(budget), cancellation::isCancelled);
    }

    public static PairReductionVerifier.Evaluation evaluate(PairReductionVerifier.Verified checked,
            Iterable<? extends List<BigInteger>> rows, OptimizationBudget budget, CancellationToken cancellation) {
        return checked.evaluate(rows, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
