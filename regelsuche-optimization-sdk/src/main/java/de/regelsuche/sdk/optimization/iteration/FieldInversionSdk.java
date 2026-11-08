package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.iteration.FieldInversionReduction;
import de.regelsuche.math.algorithms.iteration.FieldInversionPlan;
import de.regelsuche.math.algorithms.iteration.FieldInversionSearch;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;

/** SDK access to checked products and invariant inversion schedules. */
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

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
