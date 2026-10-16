package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Synthetic version of the missed Paeth arithmetic, with its actual eight-operation trace. */
class SharedAdditiveOptimizationTest {
    @Test
    void findsSharedSignedDifferencesWithoutChangingJavaOverflowSemantics() {
        for (NumericKind kind : List.of(NumericKind.INT, NumericKind.LONG)) {
            var request = request(kind);
            var result = new ComputationOptimizer().optimize(request, CancellationToken.NONE);
            var candidate = assertInstanceOf(OptimizationResult.Candidate.class, result, kind + ": " + result);
            assertTrue(candidate.cost().estimatedRuntimeImprovement());
            long operations = candidate.prepared().nodes().stream()
                    .filter(node -> node.operation() != null && !JavaExpressions.isLiteral(node.expression())).count();
            assertTrue(operations <= 6, "Expected at most three signed arithmetic operations plus three abs calls: " + operations);
            assertInstanceOf(VerificationResult.Verified.class,
                    new ComputationOptimizer().reverify(request, candidate, CancellationToken.NONE));
            compareFullRange(request, candidate.plan(), kind);
        }
    }

    @Test
    void independentCheckerAlreadyAcceptsTheSharedCandidate() {
        for (NumericKind kind : List.of(NumericKind.INT, NumericKind.LONG)) {
            var request = request(kind);
            Expr ac = op(kind, NumericOperation.SUBTRACT, new VariableExpr("a"), new VariableExpr("c"));
            Expr bc = op(kind, NumericOperation.SUBTRACT, new VariableExpr("b"), new VariableExpr("c"));
            var candidate = request.plan().withOutputs(List.of(
                    op(kind, NumericOperation.ABS, bc), op(kind, NumericOperation.ABS, ac),
                    op(kind, NumericOperation.ABS, op(kind, NumericOperation.ADD, ac, bc))));
            assertInstanceOf(VerificationResult.Verified.class,
                    new ComputationOptimizer().verify(request, candidate, CancellationToken.NONE));
            compareFullRange(request, candidate, kind);
        }
    }

    private static OptimizationRequest request(NumericKind kind) {
        Expr a = new VariableExpr("a"), b = new VariableExpr("b"), c = new VariableExpr("c");
        Expr ab = op(kind, NumericOperation.ADD, a, b);
        Expr p = op(kind, NumericOperation.SUBTRACT, ab, c);
        Expr da = op(kind, NumericOperation.SUBTRACT, p, a);
        Expr pa = op(kind, NumericOperation.ABS, da);
        Expr db = op(kind, NumericOperation.SUBTRACT, p, b);
        Expr pb = op(kind, NumericOperation.ABS, db);
        Expr dc = op(kind, NumericOperation.SUBTRACT, p, c);
        Expr pc = op(kind, NumericOperation.ABS, dc);
        var plan = new JointComputationPlan(Map.of("a", kind.type(), "b", kind.type(), "c", kind.type()),
                Map.of(), List.of(new JointComputationPlan.Output("pa", kind.type(), pa),
                        new JointComputationPlan.Output("pb", kind.type(), pb),
                        new JointComputationPlan.Output("pc", kind.type(), pc)));
        var trace = new ArrayList<SourceEvaluationTrace.Occurrence>();
        for (Expr expression : List.of(ab, p, da, pa, db, pb, dc, pc))
            trace.add(new SourceEvaluationTrace.Occurrence("source-" + trace.size(), expression, kind, kind));
        return new OptimizationRequest(plan, new SourceEvaluationTrace(trace), Set.of(kind),
                ComputationOptimizer.SEMANTICS_REVISION, Set.of(), SafetyProfile.PRESERVE_JAVA,
                OptimizationGoal.LOWER_ESTIMATED_RUNTIME, new OptimizationBudget(1_000_000, 20_000, 32, 60_000),
                CheckedPolicy.NONE);
    }

    private static Expr op(NumericKind kind, NumericOperation operation, Expr... operands) {
        return JavaExpressions.operation(kind, operation, operands);
    }

    private static void compareFullRange(OptimizationRequest request, JointComputationPlan candidate, NumericKind kind) {
        var original = ComputationOptimizer.prepare(request.plan());
        var replacement = ComputationOptimizer.prepare(candidate);
        long min = kind == NumericKind.INT ? Integer.MIN_VALUE : Long.MIN_VALUE;
        long max = kind == NumericKind.INT ? Integer.MAX_VALUE : Long.MAX_VALUE;
        long[] edges = {min, min + 1, -2, -1, 0, 1, 2, max - 1, max};
        for (long a : edges) for (long b : edges) for (long c : edges) {
            Map<String, Object> input = values(kind, a, b, c);
            assertEquals(original.execute(input), replacement.execute(input), input.toString());
        }
        var random = new SplittableRandom(0x5041455448L);
        for (int i = 0; i < 10_000; i++) {
            Map<String, Object> input = values(kind, random.nextLong(), random.nextLong(), random.nextLong());
            assertEquals(original.execute(input), replacement.execute(input), input.toString());
        }
    }

    private static Map<String, Object> values(NumericKind kind, long a, long b, long c) {
        if (kind == NumericKind.INT) return Map.of("a", (int) a, "b", (int) b, "c", (int) c);
        return Map.of("a", a, "b", b, "c", c);
    }
}
