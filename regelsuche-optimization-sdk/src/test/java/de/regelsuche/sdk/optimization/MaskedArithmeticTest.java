package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.*;
import org.junit.jupiter.api.Test;

class MaskedArithmeticTest {
    private static final Expr X = new VariableExpr("x"), Y = new VariableExpr("y");
    @Test void lowWordArithmeticDiscardsRedundantOperandMasksWithIndependentProof() {
        for (var kind : List.of(NumericKind.INT, NumericKind.LONG)) for (int width : new int[]{1, 8, 16, 31}) {
            Expr mask = literal(kind, (1L << width) - 1);
            for (var operation : List.of(NumericOperation.ADD, NumericOperation.SUBTRACT, NumericOperation.MULTIPLY)) {
                Expr source = op(kind, NumericOperation.AND, op(kind, operation,
                        op(kind, NumericOperation.AND, X, mask), op(kind, NumericOperation.AND, Y, mask)), mask);
                Expr target = op(kind, NumericOperation.AND, op(kind, operation, X, Y), mask);
                var request = request(kind, source);
                assertInstanceOf(VerificationResult.Verified.class, new ComputationOptimizer().verify(request, plan(kind, target), CancellationToken.NONE));
                var candidate = assertInstanceOf(OptimizationResult.Candidate.class, new ComputationOptimizer().optimize(request, CancellationToken.NONE));
                assertTrue(candidate.cost().estimatedRuntimeImprovement());
                assertInstanceOf(VerificationResult.Verified.class, new ComputationOptimizer().reverify(request, candidate, CancellationToken.NONE));
            }
        }
    }
    @Test void additionOfDisjointHighAndLowWordsReconstructsTheOriginalValue() {
        var kind = NumericKind.LONG;
        Expr high = op(kind, NumericOperation.SHIFT_LEFT, op(kind, NumericOperation.UNSIGNED_SHIFT_RIGHT, X, JavaExpressions.literal(32)), JavaExpressions.literal(32));
        Expr low = op(kind, NumericOperation.AND, X, literal(kind, 0xffffffffL));
        var request = request(kind, op(kind, NumericOperation.ADD, high, low));
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, new ComputationOptimizer().optimize(request, CancellationToken.NONE));
        assertEquals(List.of(X), candidate.plan().outputExpressions());
        assertInstanceOf(VerificationResult.Verified.class, new ComputationOptimizer().reverify(request, candidate, CancellationToken.NONE));
    }
    @Test void noncontiguousMasksAndOverlappingCarryBitsAreNotDiscarded() {
        var kind = NumericKind.INT;
        Expr mask = literal(kind, 0xfe);
        Expr masked = op(kind, NumericOperation.AND, op(kind, NumericOperation.ADD, op(kind, NumericOperation.AND, X, mask), Y), mask);
        Expr wrong = op(kind, NumericOperation.AND, op(kind, NumericOperation.ADD, X, Y), mask);
        assertFalse(new ComputationOptimizer().verify(request(kind, masked), plan(kind, wrong), CancellationToken.NONE) instanceof VerificationResult.Verified);
        Expr sum = op(kind, NumericOperation.ADD, X, X), joined = op(kind, NumericOperation.OR, X, X);
        assertFalse(new ComputationOptimizer().verify(request(kind, sum), plan(kind, joined), CancellationToken.NONE) instanceof VerificationResult.Verified);
    }

    @Test void repeatedMaskedWordReductionsKeepTheSharedGraphWithinBudget() {
        for (boolean narrow : new boolean[]{false, true}) {
        var kind = NumericKind.INT;
        Expr input = narrow ? JavaExpressions.cast(NumericKind.SHORT, kind, X) : X;
        Expr value = op(kind, NumericOperation.AND, input, literal(kind, 0xffff));
        for (int shift : new int[]{8, 4, 2, 2}) value = op(kind, NumericOperation.ADD,
                op(kind, NumericOperation.UNSIGNED_SHIFT_RIGHT, value, literal(kind, shift)),
                op(kind, NumericOperation.AND, value, literal(kind, (1 << shift) - 1)));
        Expr difference = op(kind, NumericOperation.SUBTRACT, value, literal(kind, 3));
        Expr mask = op(kind, NumericOperation.SHIFT_RIGHT, difference, literal(kind, 31));
        Expr choice = op(kind, NumericOperation.XOR, op(kind, NumericOperation.AND, mask, value),
                op(kind, NumericOperation.AND, op(kind, NumericOperation.NOT, mask), difference));
        if (narrow) choice = JavaExpressions.cast(kind, NumericKind.SHORT, choice);
        var plan = new JointComputationPlan(Map.of("x", (narrow ? NumericKind.SHORT : kind).type()), Map.of(),
                List.of(new JointComputationPlan.Output("out", (narrow ? NumericKind.SHORT : kind).type(), choice)));
        var original = new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(NumericKind.SHORT, kind),
                ComputationOptimizer.SEMANTICS_REVISION, Set.of(), SafetyProfile.PRESERVE_JAVA, OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
                new OptimizationBudget(200_000, 20_000, 64, 5000), CheckedPolicy.NONE);
        var result = new ComputationOptimizer().optimize(original, CancellationToken.NONE);
        assertInstanceOf(OptimizationResult.Candidate.class, result, "narrow=" + narrow + ": " + (result instanceof OptimizationResult.Candidate ? "candidate" : result));
        }
    }
    private static Expr literal(NumericKind kind, long value) { return kind == NumericKind.LONG ? JavaExpressions.literal(value) : JavaExpressions.literal((int)value); }
    private static Expr op(NumericKind kind, NumericOperation operation, Expr... args) { return JavaExpressions.operation(kind, operation, args); }
    private static JointComputationPlan plan(NumericKind kind, Expr expression) {
        return new JointComputationPlan(Map.of("x", kind.type(), "y", kind.type()), Map.of(), List.of(new JointComputationPlan.Output("out", kind.type(), expression)));
    }
    private static OptimizationRequest request(NumericKind kind, Expr expression) {
        var plan = plan(kind, expression);
        return new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(NumericKind.INT, NumericKind.LONG),
                ComputationOptimizer.SEMANTICS_REVISION, Set.of(), SafetyProfile.PRESERVE_JAVA, OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
                new OptimizationBudget(2_000_000, 20000, 64, 5000), CheckedPolicy.NONE);
    }
}
