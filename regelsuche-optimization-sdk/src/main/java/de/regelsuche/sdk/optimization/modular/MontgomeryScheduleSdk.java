package de.regelsuche.sdk.optimization.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.MontgomeryReductionPlanner;
import de.regelsuche.math.algorithms.modular.MontgomerySchedule;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;

/** Typed access to range-checked Montgomery schedules; source-language observations remain the consumer's duty. */
public final class MontgomeryScheduleSdk {
    private MontgomeryScheduleSdk() {}

    public static MontgomerySchedule.Result certify(MontgomerySchedule.Request request,
            OptimizationBudget budget, CancellationToken cancellation) {
        return MontgomerySchedule.certify(request, limits(budget), cancellation::isCancelled);
    }

    public static MontgomerySchedule.Verification verify(MontgomerySchedule.Request request,
            MontgomerySchedule.Certificate certificate, OptimizationBudget budget, CancellationToken cancellation) {
        return MontgomerySchedule.verify(request, certificate, limits(budget), cancellation::isCancelled);
    }

    public static MontgomerySchedule.Result dotProduct(MontgomerySchedule.Parameters parameters,
            List<MontgomerySchedule.Input> inputs, List<MontgomeryReductionPlanner.Product> products,
            MontgomerySchedule.Word sourceSumWord, MontgomerySchedule.Observation observation,
            OptimizationBudget budget, CancellationToken cancellation) {
        return MontgomeryReductionPlanner.dotProduct(parameters, inputs, products, sourceSumWord, observation,
                limits(budget), cancellation::isCancelled);
    }

    public static MontgomerySchedule.Result quadraticDotProduct(MontgomerySchedule.Parameters parameters,
            List<MontgomerySchedule.Input> inputs, List<MontgomeryReductionPlanner.QuadraticProduct> products,
            int zeta, MontgomeryReductionPlanner.QuadraticStrategy strategy, MontgomerySchedule.Observation observation,
            OptimizationBudget budget, CancellationToken cancellation) {
        return MontgomeryReductionPlanner.quadraticDotProduct(parameters, inputs, products, zeta, strategy,
                observation, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
