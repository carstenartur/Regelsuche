package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.*;
import org.junit.jupiter.api.Test;

class InconclusiveWorkTest {
    @Test void incompleteSearchReportsSpentWorkRatherThanConsumingAnUnknownAllowance() {
        var difference = JavaExpressions.operation(NumericKind.LONG, NumericOperation.SUBTRACT, new VariableExpr("n"), new VariableExpr("y"));
        var sign = JavaExpressions.operation(NumericKind.LONG, NumericOperation.UNSIGNED_SHIFT_RIGHT, difference, JavaExpressions.literal(63));
        var expression = JavaExpressions.operation(NumericKind.LONG, NumericOperation.SUBTRACT, JavaExpressions.cast(NumericKind.INT, NumericKind.LONG, JavaExpressions.literal(1)), sign);
        var plan = new JointComputationPlan(Map.of("n", NumericKind.LONG.type(), "y", NumericKind.LONG.type()), Map.of(),
                List.of(new JointComputationPlan.Output("result", NumericKind.LONG.type(), expression)));
        var request = new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(NumericKind.LONG, NumericKind.INT),
                ComputationOptimizer.SEMANTICS_REVISION, Set.of(), SafetyProfile.PRESERVE_JAVA, OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
                new OptimizationBudget(6000, 20000, 64, 5000), CheckedPolicy.NONE);
        var result = assertInstanceOf(OptimizationResult.Inconclusive.class, new ComputationOptimizer().optimize(request, CancellationToken.NONE));
        long work = result.work();
        assertEquals(-1, new OptimizationResult.Inconclusive("legacy").work());
        assertTrue(work > 0 && work < request.budget().maximumWork(), "Actual work allows a bounded source adapter to retain the unused allowance");
    }
}
