package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.JointComputationPlan.Output;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A rejected rewrite must not hide an independent, provably safe rewrite. */
class IndependentCandidateRegressionTest {
    private static final Expr X = new VariableExpr("x");
    private static final Expr Y = new VariableExpr("y");
    private final ComputationOptimizer optimizer = new ComputationOptimizer();

    @Test
    void unsafeSecondOutputDoesNotBlockSafeFirstOutput() {
        Expr safe = subtract(add(X, literal(1)), literal(1));
        Expr unsafe = divide(multiply(Y, literal(2)), literal(2));
        var source = plan(new Output("first", NumericKind.INT.type(), safe),
                new Output("second", NumericKind.INT.type(), unsafe));
        var request = OptimizationContractTest.request(source, SafetyProfile.PRESERVE_JAVA);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class,
                optimizer.optimize(request, CancellationToken.NONE));

        assertEquals(X, candidate.plan().outputExpressions().getFirst());
        assertEquals(unsafe, candidate.plan().outputExpressions().get(1));
        assertInstanceOf(VerificationResult.Verified.class,
                optimizer.reverify(request, candidate, CancellationToken.NONE));
        assertValues(source, candidate);
    }

    @Test
    void unsafeSiblingWithinOneOutputDoesNotBlockSafeSubexpression() {
        Expr safe = subtract(add(X, literal(1)), literal(1));
        Expr unsafe = divide(multiply(Y, literal(2)), literal(2));
        var source = plan(new Output("result", NumericKind.INT.type(), add(safe, unsafe)));
        var request = OptimizationContractTest.request(source, SafetyProfile.PRESERVE_JAVA);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class,
                optimizer.optimize(request, CancellationToken.NONE));

        assertEquals(add(X, unsafe), candidate.plan().outputExpressions().getFirst());
        assertInstanceOf(VerificationResult.Verified.class,
                optimizer.reverify(request, candidate, CancellationToken.NONE));
        assertValues(source, candidate);
    }

    @Test
    void independentCandidateDoesNotAuthorizeUnsafeCombinedReplacement() {
        Expr safe = subtract(add(X, literal(1)), literal(1));
        Expr unsafe = divide(multiply(Y, literal(2)), literal(2));
        var source = plan(new Output("result", NumericKind.INT.type(), add(safe, unsafe)));
        var request = OptimizationContractTest.request(source, SafetyProfile.PRESERVE_JAVA);
        assertInstanceOf(VerificationResult.Refuted.class,
                optimizer.verify(request, source.withOutputs(List.of(add(X, Y))), CancellationToken.NONE));
    }

    @Test
    void identicalRequestRetainsDeterministicCandidatesAndEvidence() {
        var source = plan(new Output("result", NumericKind.INT.type(),
                add(subtract(add(X, literal(1)), literal(1)),
                        divide(multiply(Y, literal(2)), literal(2)))));
        var request = OptimizationContractTest.request(source, SafetyProfile.PRESERVE_JAVA);
        var first = assertInstanceOf(OptimizationResult.Candidate.class,
                optimizer.optimize(request, CancellationToken.NONE));
        var second = assertInstanceOf(OptimizationResult.Candidate.class,
                optimizer.optimize(request, CancellationToken.NONE));
        assertEquals(first.plan(), second.plan());
        assertEquals(first.evidence(), second.evidence());
        assertEquals(first.cost(), second.cost());
    }

    private static void assertValues(JointComputationPlan source, OptimizationResult.Candidate candidate) {
        var original = ComputationOptimizer.prepare(source);
        int[] edges = {Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 1, -1, 1 << 30, -(1 << 30)};
        for (int x : edges) for (int y : edges) {
            var inputs = Map.<String, Object>of("x", x, "y", y);
            assertEquals(original.execute(inputs), candidate.prepared().execute(inputs),
                    "x=" + x + ", y=" + y);
        }
    }

    private static JointComputationPlan plan(Output... outputs) {
        return new JointComputationPlan(Map.of("x", NumericKind.INT.type(), "y", NumericKind.INT.type()),
                Map.of(), List.of(outputs));
    }
    private static Expr literal(int value) { return JavaExpressions.literal(value); }
    private static Expr add(Expr left, Expr right) { return operation(NumericOperation.ADD, left, right); }
    private static Expr subtract(Expr left, Expr right) { return operation(NumericOperation.SUBTRACT, left, right); }
    private static Expr multiply(Expr left, Expr right) { return operation(NumericOperation.MULTIPLY, left, right); }
    private static Expr divide(Expr left, Expr right) { return operation(NumericOperation.DIVIDE, left, right); }
    private static Expr operation(NumericOperation operation, Expr left, Expr right) {
        return JavaExpressions.operation(NumericKind.INT, operation, left, right);
    }
}
