package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.iteration.OrderedReduction;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.iteration.OrderedReductionPlan;
import de.regelsuche.math.algorithms.iteration.OrderedReductionSearch;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;

/** Delegates ordered reductions to the mathematical search, checker and evaluator. */
public final class OrderedReductionSdk {
    private OrderedReductionSdk() {}

    public static OrderedReductionSearch.Result search(OrderedReduction source, OptimizationBudget budget,
            CancellationToken cancellation) {
        return OrderedReductionSearch.search(source, limits(budget), budget.maximumStates(), cancellation::isCancelled);
    }

    public static OrderedReductionSearch.Result searchWindows(OrderedReduction source, OptimizationBudget budget,
            CancellationToken cancellation) {
        return OrderedReductionSearch.searchWindows(source, limits(budget), budget.maximumStates(), cancellation::isCancelled);
    }

    public static OrderedReductionPlan.Verification verify(OrderedReduction source, OrderedReductionPlan plan,
            boolean windows, OptimizationBudget budget, CancellationToken cancellation) {
        return OrderedReductionSearch.verify(source, plan, windows, limits(budget), cancellation::isCancelled);
    }

    public static OrderedReductionPlan.Evaluation evaluate(OrderedReductionPlan.Verified plan,
            List<? extends List<BigInteger>> rows, OptimizationBudget budget, CancellationToken cancellation) {
        return plan.evaluate(rows, limits(budget), cancellation::isCancelled);
    }

    public static OrderedReductionPlan.Evaluation evaluateWindows(OrderedReductionPlan.Verified plan,
            List<? extends List<BigInteger>> rows, int width, OptimizationBudget budget, CancellationToken cancellation) {
        return plan.evaluateWindows(rows, width, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
