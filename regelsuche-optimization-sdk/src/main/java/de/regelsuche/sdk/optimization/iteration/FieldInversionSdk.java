package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.iteration.FieldInversionBatch;
import de.regelsuche.ast.iteration.FieldInversionReduction;
import de.regelsuche.math.algorithms.iteration.FieldBatchInversionPlan;
import de.regelsuche.math.algorithms.iteration.FieldBatchInversionSearch;
import de.regelsuche.math.algorithms.iteration.FieldInversionPlan;
import de.regelsuche.math.algorithms.iteration.FieldInversionSearch;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** SDK access to checked field inversion schedules. */
public final class FieldInversionSdk {
    private FieldInversionSdk() {}

    public static FieldInversionSearch.Result search(FieldInversionReduction source, OptimizationBudget budget,
            CancellationToken cancellation) {
        return FieldInversionSearch.search(source, limits(budget), budget.maximumStates(), cancellation::isCancelled);
    }

    public static FieldInversionSearch.Verification verify(FieldInversionReduction source, FieldInversionPlan candidate,
            OptimizationBudget budget, CancellationToken cancellation) {
        return FieldInversionSearch.verify(source, candidate, limits(budget), cancellation::isCancelled);
    }

    public static FieldBatchInversionSearch.Result searchBatch(FieldInversionBatch source, OptimizationBudget budget,
            CancellationToken cancellation) {
        return FieldBatchInversionSearch.search(source, batchLimits(budget), budget.maximumStates(), cancellation::isCancelled);
    }

    public static FieldBatchInversionSearch.Verification verifyBatch(FieldInversionBatch source,
            FieldBatchInversionPlan candidate, OptimizationBudget budget, CancellationToken cancellation) {
        return FieldBatchInversionSearch.verify(source, candidate, batchLimits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }

    private static IterationLimits batchLimits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), FieldInversionBatch.MAX_OPERANDS, budget.timeoutMillis());
    }
}
