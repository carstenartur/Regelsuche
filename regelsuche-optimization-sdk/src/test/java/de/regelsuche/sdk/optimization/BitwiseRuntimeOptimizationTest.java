package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unknown inputs and universal re-verification; execution samples are only regressions. */
class BitwiseRuntimeOptimizationTest {
    private static final Expr X = new VariableExpr("x");
    private static final Expr Y = new VariableExpr("y");
    private static final Expr Z = new VariableExpr("z");

    @Test void chooseIntReducesFourOperationsToThree() {
        qualifyChoose(NumericKind.INT);
    }
    @Test void chooseLongReducesFourOperationsToThree() {
        qualifyChoose(NumericKind.LONG);
    }
    @Test void majorityIntReducesFiveOperationsToFour() {
        qualifyMajority(NumericKind.INT);
    }
    @Test void majorityLongReducesFiveOperationsToFour() {
        qualifyMajority(NumericKind.LONG);
    }
    @Test void factorsCommonMasksForBothBitWidths() {
        for (NumericKind kind : List.of(NumericKind.INT, NumericKind.LONG)) {
            for (NumericOperation join : List.of(NumericOperation.OR, NumericOperation.XOR)) {
                Expr source = op(kind, join, op(kind, NumericOperation.AND, X, Z),
                        op(kind, NumericOperation.AND, Z, Y));
                qualify(kind, source, 2);
            }
        }
    }
    @Test void differentSelectorsAreNotTreatedAsComplements() {
        NumericKind kind = NumericKind.INT;
        Expr source = op(kind, NumericOperation.OR, op(kind, NumericOperation.AND, X, Y),
                op(kind, NumericOperation.AND, op(kind, NumericOperation.NOT, Z), Z));
        Expr wrong = op(kind, NumericOperation.XOR, Z,
                op(kind, NumericOperation.AND, X, op(kind, NumericOperation.XOR, Y, Z)));
        var optimizer = new ComputationOptimizer();
        assertFalse(optimizer.verify(request(kind, source), plan(kind, wrong), CancellationToken.NONE)
                instanceof VerificationResult.Verified);
    }
    @Test void arithmeticProductsDoNotMatchBitwiseFactoring() {
        NumericKind kind = NumericKind.INT;
        Expr source = op(kind, NumericOperation.XOR, op(kind, NumericOperation.MULTIPLY, X, Z),
                op(kind, NumericOperation.MULTIPLY, Y, Z));
        Expr wrong = op(kind, NumericOperation.MULTIPLY, op(kind, NumericOperation.XOR, X, Y), Z);
        assertFalse(new ComputationOptimizer().verify(request(kind, source), plan(kind, wrong), CancellationToken.NONE)
                instanceof VerificationResult.Verified);
    }

    private static void qualifyChoose(NumericKind kind) {
        for (NumericOperation join : List.of(NumericOperation.OR, NumericOperation.XOR)) {
            Expr positive = op(kind, NumericOperation.AND, X, Y);
            Expr negative = op(kind, NumericOperation.AND, op(kind, NumericOperation.NOT, X), Z);
            qualify(kind, op(kind, join, positive, negative), 3);
            qualify(kind, op(kind, join, negative, positive), 3);
            qualify(kind, op(kind, join, op(kind, NumericOperation.AND, Y, X),
                    op(kind, NumericOperation.AND, Z, op(kind, NumericOperation.NOT, X))), 3);
        }
    }
    private static void qualifyMajority(NumericKind kind) {
        for (NumericOperation join : List.of(NumericOperation.OR, NumericOperation.XOR)) {
            Expr xy = op(kind, NumericOperation.AND, X, Y);
            Expr xz = op(kind, NumericOperation.AND, X, Z);
            Expr yz = op(kind, NumericOperation.AND, Y, Z);
            qualify(kind, op(kind, join, op(kind, join, xy, xz), yz), 4);
            qualify(kind, op(kind, join, yz, op(kind, join, xz, xy)), 4);
        }
    }
    private static void qualify(NumericKind kind, Expr expression, long maximumWork) {
        var optimizer = new ComputationOptimizer();
        var request = request(kind, expression);
        var result = optimizer.optimize(request, CancellationToken.NONE);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, result, result.toString());
        assertInstanceOf(VerificationResult.Verified.class,
                optimizer.reverify(request, candidate, CancellationToken.NONE));
        assertTrue(candidate.cost().estimatedRuntimeImprovement(), candidate.cost().toString());
        assertTrue(candidate.cost().candidateCost().operationWork() <= maximumWork, candidate.cost().toString());
        assertEquals(RuntimeObligations.GuardKind.NONE, candidate.obligations().guard());
        var original = ComputationOptimizer.prepare(request.plan());
        long[] edges = {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE,
                0x5555555555555555L, 0xaaaaaaaaaaaaaaaaL};
        for (long x : edges) for (long y : edges) for (long z : edges) {
            var inputs = inputs(kind, x, y, z);
            assertEquals(original.execute(inputs), candidate.prepared().execute(inputs), inputs.toString());
        }
        Random random = new Random(1657);
        for (int i = 0; i < 256; i++) {
            var inputs = inputs(kind, random.nextLong(), random.nextLong(), random.nextLong());
            assertEquals(original.execute(inputs), candidate.prepared().execute(inputs));
        }
        var repeated = optimizer.optimize(request(kind, candidate.plan().outputExpressions().getFirst()), CancellationToken.NONE);
        assertFalse(repeated instanceof OptimizationResult.Candidate, repeated.toString());
    }
    private static Map<String, Object> inputs(NumericKind kind, long x, long y, long z) {
        return Map.of("x", value(kind, x), "y", value(kind, y), "z", value(kind, z));
    }
    private static Object value(NumericKind kind, long value) {
        if (kind == NumericKind.INT) return Integer.valueOf((int) value);
        return Long.valueOf(value);
    }
    private static Expr op(NumericKind kind, NumericOperation operation, Expr... operands) {
        return JavaExpressions.operation(kind, operation, operands);
    }
    private static JointComputationPlan plan(NumericKind kind, Expr expression) {
        return new JointComputationPlan(Map.of("x", kind.type(), "y", kind.type(), "z", kind.type()), Map.of(),
                List.of(new JointComputationPlan.Output("out", kind.type(), expression)));
    }
    private static OptimizationRequest request(NumericKind kind, Expr expression) {
        var plan = plan(kind, expression);
        return new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(kind),
                ComputationOptimizer.SEMANTICS_REVISION, Set.of(), SafetyProfile.PRESERVE_JAVA,
                OptimizationGoal.LOWER_ESTIMATED_RUNTIME, new OptimizationBudget(1_000_000L, 20_000, 64, 5000L),
                CheckedPolicy.NONE);
    }
}
