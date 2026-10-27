package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BooleanProofOrderingTest {
    @Test void foldedWordSelectionUsesItsOpaqueProofBeforeProjectingSubterms() {
        var trace = new ArrayList<SourceEvaluationTrace.Occurrence>();
        Expr r = remember(JavaExpressions.cast(NumericKind.SHORT, NumericKind.INT, new VariableExpr("x")), trace);
        r = op(trace, NumericOperation.AND, r, JavaExpressions.literal(65535));
        for (int shift : new int[] {8, 4, 2, 2}) {
            Expr high = op(trace, NumericOperation.UNSIGNED_SHIFT_RIGHT, r, JavaExpressions.literal(shift));
            Expr low = op(trace, NumericOperation.AND, r, JavaExpressions.literal((1 << shift) - 1));
            r = op(trace, NumericOperation.ADD, high, low);
        }
        Expr t = op(trace, NumericOperation.SUBTRACT, r, JavaExpressions.literal(3));
        Expr mask = op(trace, NumericOperation.SHIFT_RIGHT, t, JavaExpressions.literal(31));
        Expr selected = op(trace, NumericOperation.XOR,
                op(trace, NumericOperation.AND, mask, r),
                op(trace, NumericOperation.AND, op(trace, NumericOperation.NOT, mask), t));
        Expr source = remember(JavaExpressions.cast(NumericKind.INT, NumericKind.SHORT, selected), trace);
        Expr target = JavaExpressions.cast(NumericKind.INT, NumericKind.SHORT,
                JavaExpressions.operation(NumericKind.INT, NumericOperation.XOR, t,
                        JavaExpressions.operation(NumericKind.INT, NumericOperation.AND, mask,
                                JavaExpressions.operation(NumericKind.INT, NumericOperation.XOR, t, r))));
        var plan = new JointComputationPlan(Map.of("x", NumericKind.SHORT.type()), Map.of(),
                List.of(new JointComputationPlan.Output("result", NumericKind.SHORT.type(), source)));
        var request = new OptimizationRequest(plan, new SourceEvaluationTrace(trace),
                Set.of(NumericKind.SHORT, NumericKind.INT), ComputationOptimizer.SEMANTICS_REVISION, Set.of(),
                SafetyProfile.PRESERVE_JAVA, OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
                new OptimizationBudget(70_000, 20_000, 64, 5000), CheckedPolicy.NONE);
        var result = new ComputationOptimizer().verify(request, plan.withOutputs(List.of(target)), CancellationToken.NONE);
        var verified = assertInstanceOf(VerificationResult.Verified.class, result, result.toString());
        assertTrue(verified.evidence().proofMethods().contains("BITVECTOR_TRUTH_TABLE"));
    }

    private static Expr op(List<SourceEvaluationTrace.Occurrence> trace, NumericOperation operation, Expr... arguments) {
        return remember(JavaExpressions.operation(NumericKind.INT, operation, arguments), trace);
    }
    private static Expr remember(Expr expression, List<SourceEvaluationTrace.Occurrence> trace) {
        var kind = JavaExpressions.resultKind(expression);
        trace.add(new SourceEvaluationTrace.Occurrence("operation-" + trace.size(), expression, kind, kind));
        return expression;
    }
}
