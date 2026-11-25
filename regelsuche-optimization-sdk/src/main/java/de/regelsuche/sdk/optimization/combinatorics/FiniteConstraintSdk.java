package de.regelsuche.sdk.optimization.combinatorics;

import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCnf;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCnfVerifier;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanLearning;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanner;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintVerifier;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;

/** Typed access to bounded exact nonnegative weighted Boolean model counting. */
public final class FiniteConstraintSdk {
    private FiniteConstraintSdk() {}

    public static FiniteConstraintCounter.Result count(FiniteConstraintCounter.Problem problem,
            OptimizationBudget budget, CancellationToken cancellation) {
        return FiniteConstraintCounter.count(problem, limits(budget), cancellation::isCancelled);
    }

    public static FiniteConstraintCounter.Result count(FiniteConstraintCounter.Problem problem,
            FiniteConstraintPlanner.Strategy strategy, OptimizationBudget budget, CancellationToken cancellation) {
        return FiniteConstraintCounter.count(problem, strategy, limits(budget), cancellation::isCancelled);
    }

    public static FiniteConstraintCounter.Result countIndexed(FiniteConstraintCounter.Problem problem,
            FiniteConstraintPlanner.Strategy strategy, OptimizationBudget budget, CancellationToken cancellation) {
        return FiniteConstraintCounter.countIndexed(problem, strategy, limits(budget), cancellation::isCancelled);
    }

    public static FiniteConstraintCnf.Lowering lowerCnf(FiniteConstraintCnf.Source source,
            OptimizationBudget budget, CancellationToken cancellation) {
        return FiniteConstraintCnf.lower(source, limits(budget), cancellation::isCancelled);
    }

    public static FiniteConstraintVerifier.Verification verifyCnfLowering(FiniteConstraintCnf.Source source,
            FiniteConstraintCounter.Problem problem, OptimizationBudget budget, CancellationToken cancellation) {
        return FiniteConstraintCnfVerifier.verifyLowering(source, problem, limits(budget), cancellation::isCancelled);
    }

    public static FiniteConstraintCnf.Result countCnf(FiniteConstraintCnf.Source source,
            OptimizationBudget budget, CancellationToken cancellation) {
        return FiniteConstraintCnf.count(source, limits(budget), cancellation::isCancelled);
    }

    public static FiniteConstraintVerifier.Verification verifyCnf(FiniteConstraintCnf.Source source,
            FiniteConstraintCnf.Certificate certificate, OptimizationBudget budget, CancellationToken cancellation) {
        return FiniteConstraintCnf.verify(source, certificate, limits(budget), cancellation::isCancelled);
    }

    public static FiniteConstraintPlanLearning.Training train(List<FiniteConstraintPlanLearning.Example> examples,
            OptimizationBudget budget, long perTrialWork, CancellationToken cancellation) {
        return FiniteConstraintPlanLearning.train(examples, limits(budget), perTrialWork, cancellation::isCancelled);
    }

    public static FiniteConstraintPlanLearning.Evaluation count(FiniteConstraintPlanLearning.Model model,
            FiniteConstraintCounter.Problem problem, OptimizationBudget budget, CancellationToken cancellation) {
        return FiniteConstraintPlanLearning.count(model, problem, limits(budget), cancellation::isCancelled);
    }

    public static FiniteConstraintVerifier.Verification verify(FiniteConstraintCounter.Problem problem,
            FiniteConstraintCounter.Certificate certificate, OptimizationBudget budget, CancellationToken cancellation) {
        return FiniteConstraintVerifier.verify(problem, certificate, limits(budget), cancellation::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
