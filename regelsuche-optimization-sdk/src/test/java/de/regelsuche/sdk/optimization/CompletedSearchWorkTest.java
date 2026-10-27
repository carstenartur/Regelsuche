package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompletedSearchWorkTest {
    @Test void innerSearchLimitDoesNotConsumeTheUnusedOuterAllowance() {
        var request = request(1000);
        var result = assertInstanceOf(OptimizationResult.BudgetExceeded.class,
                new ComputationOptimizer().optimize(request, CancellationToken.NONE));
        assertEquals("SEARCH_BUDGET_EXCEEDED", result.diagnostic());
        assertTrue(result.hasCompleteWorkAccounting());
        assertTrue(result.work() > 0 && result.work() < request.budget().maximumWork(), result.toString());
    }

    @Test void interruptedCheckerAccountingDoesNotReleaseItsReservation() {
        var result = assertInstanceOf(OptimizationResult.BudgetExceeded.class,
                new ComputationOptimizer().optimize(request(2), CancellationToken.NONE));
        assertEquals("OPTIMIZATION_BUDGET_EXCEEDED", result.diagnostic());
        assertTrue(result.work() < 2, result.toString());
        assertFalse(result.hasCompleteWorkAccounting());
    }

    @Test void legacyOrUnknownCountersCannotClaimCompleteAccounting() {
        assertFalse(new OptimizationResult.BudgetExceeded("SEARCH_BUDGET_EXCEEDED", -1).hasCompleteWorkAccounting());
        assertFalse(new OptimizationResult.BudgetExceeded("future-stage", 10).hasCompleteWorkAccounting());
        assertFalse(new OptimizationResult.BudgetExceeded(null, 10).hasCompleteWorkAccounting());
        assertTrue(new OptimizationResult.BudgetExceeded("SEARCH_BUDGET_EXCEEDED", 100_001).hasCompleteWorkAccounting());
    }

    private static OptimizationRequest request(long maximumWork) {
        var expression = JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD,
                new VariableExpr("x"), new VariableExpr("y"));
        var plan = new JointComputationPlan(Map.of("x", NumericKind.INT.type(), "y", NumericKind.INT.type()), Map.of(),
                List.of(new JointComputationPlan.Output("result", NumericKind.INT.type(), expression)));
        return new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(NumericKind.INT),
                ComputationOptimizer.SEMANTICS_REVISION, Set.of(), SafetyProfile.PRESERVE_JAVA,
                OptimizationGoal.LOWER_ESTIMATED_RUNTIME, new OptimizationBudget(maximumWork, 1, 64, 5000), CheckedPolicy.NONE);
    }
}
