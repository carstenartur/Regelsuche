package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.search.program.ComputationBackend.Type;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.*;
import org.junit.jupiter.api.Test;

/** A complete eight-word carry computation, including every intermediate commit. */
class CarryPlanTransportTest {
    @Test void completeCarryPlanSurvivesColdTransportAndActualSearch() {
        var source = plan();
        var codec = new CompiledAstReplayCodec();
        var restored = source.withExpression(codec.decodeExpression(codec.encodeExpression(source.expression())));
        var before = ComputationOptimizer.prepare(source);
        var after = assertDoesNotThrow(() -> ComputationOptimizer.prepare(restored));
        assertEquals(87, before.nodes().size());
        assertEquals(before.nodes(), after.nodes());
        assertTrue(after.cost().inspectionWork() > before.cost().inspectionWork());
        var request = new OptimizationRequest(source, SourceEvaluationTrace.fromPlan(source), Set.of(NumericKind.INT, NumericKind.LONG),
                ComputationOptimizer.SEMANTICS_REVISION, Set.of(), SafetyProfile.PRESERVE_JAVA, OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
                new OptimizationBudget(1_000_000, 20_000, 64, 5000), CheckedPolicy.NONE);
        var result = new ComputationOptimizer().optimize(request, CancellationToken.NONE);
        assertFalse(result instanceof OptimizationResult.Unsupported, result.toString());
        assertFalse(result instanceof OptimizationResult.Refuted, result.toString());
        if (result instanceof OptimizationResult.Candidate candidate)
            assertInstanceOf(VerificationResult.Verified.class, new ComputationOptimizer().reverify(request, candidate, CancellationToken.NONE));
    }

    private static JointComputationPlan plan() {
        Map<String, Type> inputs = new LinkedHashMap<>();
        List<JointComputationPlan.Output> outputs = new ArrayList<>();
        Expr carry = JavaExpressions.literal(0L);
        output(outputs, NumericKind.LONG, carry);
        for (int i = 0; i < 8; i++) {
            String left = "left" + i, right = "right" + i;
            inputs.put(left, NumericKind.INT.type()); inputs.put(right, NumericKind.INT.type());
            Expr a = op(NumericOperation.AND, JavaExpressions.cast(NumericKind.INT, NumericKind.LONG, new VariableExpr(left)), JavaExpressions.literal(0xffffffffL));
            Expr b = op(NumericOperation.AND, JavaExpressions.cast(NumericKind.INT, NumericKind.LONG, new VariableExpr(right)), JavaExpressions.literal(0xffffffffL));
            Expr sum = op(NumericOperation.ADD, carry, op(NumericOperation.ADD, a, b));
            output(outputs, NumericKind.LONG, sum);
            output(outputs, NumericKind.INT, JavaExpressions.cast(NumericKind.LONG, NumericKind.INT, sum));
            carry = op(NumericOperation.UNSIGNED_SHIFT_RIGHT, sum, JavaExpressions.literal(32));
            output(outputs, NumericKind.LONG, carry);
        }
        output(outputs, NumericKind.INT, JavaExpressions.cast(NumericKind.LONG, NumericKind.INT, carry));
        return new JointComputationPlan(inputs, Map.of(), outputs);
    }
    private static Expr op(NumericOperation op, Expr... values) { return JavaExpressions.operation(NumericKind.LONG, op, values); }
    private static void output(List<JointComputationPlan.Output> outputs, NumericKind kind, Expr value) {
        outputs.add(new JointComputationPlan.Output("out" + outputs.size(), kind.type(), value));
    }
}
