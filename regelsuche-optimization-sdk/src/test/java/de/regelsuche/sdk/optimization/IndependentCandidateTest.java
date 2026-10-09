package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.JointComputationPlan.Output;
import java.math.BigInteger;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Exercises the real optimizer, including its independent checker, with unknown inputs. */
class IndependentCandidateTest {
    private static final Expr X = new VariableExpr("x");
    private static final Expr Y = new VariableExpr("y");
    private final ComputationOptimizer optimizer = new ComputationOptimizer();

    @Test
    void rejectedOverflowCancellationDoesNotHideAnIndependentSafeOutput() {
        Expr unsafe = divide(multiply(Y, 2), 2);
        var plan = integers(List.of(output("safe", subtract(add(X, 1), 1)), output("keep", unsafe)));
        var request = request(plan, Set.of());
        var result = optimizer.optimize(request, CancellationToken.NONE);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, result, result::toString);
        assertEquals(X, candidate.plan().outputExpressions().getFirst());
        assertEquals(unsafe, candidate.plan().outputExpressions().get(1));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        for (int x : new int[] {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            var inputs = Map.<String, Object>of("x", x, "y", Integer.MAX_VALUE);
            assertEquals(ComputationOptimizer.prepare(plan).execute(inputs), candidate.prepared().execute(inputs));
            assertEquals(-1, candidate.prepared().execute(inputs).get("keep"));
        }
    }

    @Test
    void rejectedSiblingDoesNotHideASafeRewriteInsideTheSameOutput() {
        Expr unsafe = divide(multiply(Y, 2), 2);
        Expr original = operation(NumericOperation.ADD, subtract(add(X, 1), 1), unsafe);
        var plan = integers(List.of(output("result", original)));
        var request = request(plan, Set.of());
        var result = optimizer.optimize(request, CancellationToken.NONE);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, result, result::toString);
        assertEquals(operation(NumericOperation.ADD, X, unsafe), candidate.plan().outputExpressions().getFirst());
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        var inputs = Map.<String, Object>of("x", Integer.MAX_VALUE, "y", Integer.MIN_VALUE);
        assertEquals(ComputationOptimizer.prepare(plan).execute(inputs), candidate.prepared().execute(inputs));
    }

    @Test
    void unrelatedPrimitiveOutputDoesNotDisableSharedModularCandidates() {
        Expr a = new VariableExpr("a");
        Expr e = new VariableExpr("e");
        Expr modulus = JavaExpressions.literal(BigInteger.valueOf(65537));
        Expr one = JavaExpressions.literal(BigInteger.ONE);
        Expr two = JavaExpressions.literal(BigInteger.TWO);
        Expr first = big(NumericOperation.MOD_POW, a, big(NumericOperation.ADD, e, one), modulus);
        Expr second = big(NumericOperation.MOD_POW, a,
                big(NumericOperation.ADD, big(NumericOperation.MULTIPLY, e, two), one), modulus);
        var plan = new JointComputationPlan(
                Map.of("a", NumericKind.BIG_INTEGER.type(), "e", NumericKind.BIG_INTEGER.type(), "x", NumericKind.INT.type()),
                Map.of(), List.of(new Output("first", NumericKind.BIG_INTEGER.type(), first),
                        new Output("second", NumericKind.BIG_INTEGER.type(), second), output("unchanged", add(X, 7))));
        var assumptions = Set.of(
                new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, "a", "", "test contract"),
                new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, "e", "", "test contract"),
                new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, "a", "64", "test bound"),
                new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, "e", "31", "test bound"),
                new SemanticAssumption(SemanticAssumption.Kind.NON_NEGATIVE, "e", "", "test range"));
        var request = request(plan, assumptions);
        var result = optimizer.optimize(request, CancellationToken.NONE);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, result, result::toString);
        assertEquals(add(X, 7), candidate.plan().outputExpressions().get(2));
        assertTrue(candidate.cost().estimatedRuntimeImprovement());
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        for (int exponent : new int[] {0, 1, 7, 31}) {
            var inputs = Map.<String, Object>of("a", BigInteger.valueOf(-10), "e", BigInteger.valueOf(exponent), "x", Integer.MAX_VALUE);
            assertEquals(ComputationOptimizer.prepare(plan).execute(inputs), candidate.prepared().execute(inputs));
        }
    }

    @Test
    void candidateCapAndOrderingAreDeterministic() {
        var plan = integers(List.of(output("first", subtract(add(X, 1), 1)), output("second", subtract(add(Y, 1), 1))));
        var request = request(plan, Set.of());
        var first = new JavaCandidateGenerator(request, new VerificationWork(request, CancellationToken.NONE)).generate(plan, 1);
        var second = new JavaCandidateGenerator(request, new VerificationWork(request, CancellationToken.NONE)).generate(plan, 1);
        assertTrue(first.proposals().size() <= 1);
        assertEquals(first.proposals(), second.proposals());
        assertEquals(first.complete(), second.complete());
        assertTrue(first.work() > 0);
    }

    private static OptimizationRequest request(JointComputationPlan plan, Set<SemanticAssumption> assumptions) {
        return new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), EnumSet.allOf(NumericKind.class),
                ComputationOptimizer.SEMANTICS_REVISION, assumptions, SafetyProfile.PRESERVE_JAVA,
                OptimizationGoal.LOWER_ESTIMATED_RUNTIME, new OptimizationBudget(1_000_000L, 20_000, 64, 10_000L), CheckedPolicy.NONE);
    }
    private static JointComputationPlan integers(List<Output> outputs) {
        return new JointComputationPlan(Map.of("x", NumericKind.INT.type(), "y", NumericKind.INT.type()), Map.of(), outputs);
    }
    private static Output output(String name, Expr expression) { return new Output(name, NumericKind.INT.type(), expression); }
    private static Expr operation(NumericOperation op, Expr... operands) { return JavaExpressions.operation(NumericKind.INT, op, operands); }
    private static Expr big(NumericOperation op, Expr... operands) { return JavaExpressions.operation(NumericKind.BIG_INTEGER, op, operands); }
    private static Expr add(Expr value, int number) { return operation(NumericOperation.ADD, value, JavaExpressions.literal(number)); }
    private static Expr subtract(Expr value, int number) { return operation(NumericOperation.SUBTRACT, value, JavaExpressions.literal(number)); }
    private static Expr multiply(Expr value, int number) { return operation(NumericOperation.MULTIPLY, value, JavaExpressions.literal(number)); }
    private static Expr divide(Expr value, int number) { return operation(NumericOperation.DIVIDE, value, JavaExpressions.literal(number)); }
}
