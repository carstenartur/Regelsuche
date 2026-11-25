package de.regelsuche.sdk.optimization.algebra;

import de.regelsuche.math.algorithms.algebra.OccupiedWorkspace;
import de.regelsuche.math.algorithms.algebra.OperatorResidualPlans;
import de.regelsuche.math.algorithms.algebra.OperatorResidualSearch;
import de.regelsuche.math.algorithms.algebra.PreparedOperator;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.scalar.ExactRational;
import java.util.List;

import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.*;

/** Bounded SDK entry points for exact operator repairs and restored workspace. */
public final class OperatorPlanSdk {
    private OperatorPlanSdk() {}

    public static Result<OperatorResidualSearch.Discovery> discover(PolynomialMatrix source,
            OperatorResidualSearch.Policy policy, OptimizationBudget budget, CancellationToken cancel) {
        return OperatorResidualSearch.discover(source, policy, budget.maximumCandidates(), limits(budget), cancel::isCancelled);
    }

    public static Result<PreparedOperator> prepare(PolynomialMatrix source, Plan plan, OptimizationBudget budget,
            CancellationToken cancel) {
        return OperatorResidualSearch.prepare(source, plan, limits(budget), cancel::isCancelled);
    }

    public static Result<List<ExactRational>> apply(PreparedOperator prepared, List<ExactRational> vector,
            OptimizationBudget budget, CancellationToken cancel) {
        return prepared.apply(vector, limits(budget), cancel::isCancelled);
    }

    public static Result<Plan> spectral(PolynomialMatrix source, ExactMatrixExpression predictor,
            PolynomialMatrix basis, PolynomialMatrix inverse, OptimizationBudget budget, CancellationToken cancel) {
        return OperatorResidualPlans.spectral(source, predictor, basis, inverse, limits(budget), cancel::isCancelled);
    }

    public static Result<Cost> verify(PolynomialMatrix source, Plan plan, OptimizationBudget budget,
            CancellationToken cancel) {
        return OperatorResidualPlans.verify(source, plan, limits(budget), cancel::isCancelled);
    }

    public static Result<Selection> select(PolynomialMatrix source, List<Plan> plans, OptimizationBudget budget,
            CancellationToken cancel) {
        return OperatorResidualPlans.select(source, plans, budget.maximumCandidates(), limits(budget), cancel::isCancelled);
    }

    public static Result<OccupiedWorkspace.Comparison> verifyWorkspace(PolynomialMatrix first, PolynomialMatrix second,
            OccupiedWorkspace.Schedule schedule, OptimizationBudget budget, CancellationToken cancel) {
        return OccupiedWorkspace.verify(first, second, schedule, limits(budget), cancel::isCancelled);
    }

    public static Result<OccupiedWorkspace.State> applyWorkspace(PolynomialMatrix first, PolynomialMatrix second,
            OccupiedWorkspace.Schedule schedule, OccupiedWorkspace.State input, OptimizationBudget budget,
            CancellationToken cancel) {
        return OccupiedWorkspace.apply(first, second, schedule, input, limits(budget), cancel::isCancelled);
    }

    private static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
