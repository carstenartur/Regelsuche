package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UnsupportedPreparationWorkTest {
    @Test void initialPreparationFailureLeavesUnusedAllowanceForSmallerRequests() {
        var large = plan(sum(0, 1024));
        var request = request(large, SourceEvaluationTrace.fromPlan(large));
        var result = failure(request);
        assertTrue(result.work() > 0 && result.work() < 200_000, result.toString());
        assertTrue(result.work() < request.budget().maximumWork());
    }

    @Test void lateTraceFailureIncludesEarlierSuccessfulTraceChecks() {
        var trace = SourceEvaluationTrace.fromPlan(plan(sum(0, 1024)));
        var request = request(plan(new VariableExpr("x")), trace);
        assertDoesNotThrow(() -> ComputationOptimizer.prepare(request.plan()));
        var prefix = request(request.plan(), new SourceEvaluationTrace(
                trace.occurrences().subList(0, trace.occurrences().size() - 1)));
        var completed = new VerificationWork(prefix, CancellationToken.NONE);
        new SemanticChecker(prefix, completed).validate();
        var result = failure(request);
        assertTrue(result.work() >= completed.used() + JointComputationPlan.MAX_NODES,
                "Retain the independently validated trace prefix and failed preparation: " + result);
        assertTrue(result.work() < request.budget().maximumWork(), result.toString());
    }

    @Test void unrelatedAndLegacyUnsupportedResultsKeepUnknownWork() {
        assertEquals(-1, new OptimizationResult.Unsupported("manual").work());
        assertThrows(IllegalArgumentException.class, () -> new OptimizationResult.Unsupported("invalid", -2));
        var original = request(plan(new VariableExpr("x")), new SourceEvaluationTrace(List.of()));
        var unknown = new OptimizationRequest(original.plan(), original.sourceTrace(), original.selectedKinds(),
                "unknown", original.assumptions(), original.safetyProfile(), original.goal(),
                original.budget(), original.checkedPolicy());
        assertEquals(-1, assertInstanceOf(OptimizationResult.Unsupported.class,
                new ComputationOptimizer().optimize(unknown, CancellationToken.NONE)).work());
    }

    private static OptimizationResult.Unsupported failure(OptimizationRequest request) {
        var result = assertInstanceOf(OptimizationResult.Unsupported.class,
                new ComputationOptimizer().optimize(request, CancellationToken.NONE));
        assertTrue(result.diagnostic().contains("plan preparation work bound exceeded"), result.toString());
        return result;
    }
    private static OptimizationRequest request(JointComputationPlan plan, SourceEvaluationTrace trace) {
        return new OptimizationRequest(plan, trace, Set.of(NumericKind.INT),
                ComputationOptimizer.SEMANTICS_REVISION, Set.of(), SafetyProfile.PRESERVE_JAVA,
                OptimizationGoal.LOWER_ESTIMATED_RUNTIME, new OptimizationBudget(2_000_000, 20_000, 64, 5000),
                CheckedPolicy.NONE);
    }
    private static JointComputationPlan plan(Expr expression) {
        return new JointComputationPlan(Map.of("x", NumericKind.INT.type()), Map.of(),
                List.of(new JointComputationPlan.Output("result", NumericKind.INT.type(), expression)));
    }
    private static Expr sum(int first, int count) {
        if (count == 1) return JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD,
                new VariableExpr("x"), JavaExpressions.literal(first));
        int left = count / 2;
        return JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD,
                sum(first, left), sum(first + left, count - left));
    }
}
