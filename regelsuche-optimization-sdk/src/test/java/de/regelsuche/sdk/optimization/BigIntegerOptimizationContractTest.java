package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.JointComputationPlan.Output;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Fresh end-to-end contracts for the public typed facade and existing modular checker. */
class BigIntegerOptimizationContractTest {
    private final ComputationOptimizer optimizer = new ComputationOptimizer();
    private static final Expr A = new VariableExpr("a"), X = new VariableExpr("x"), N = new VariableExpr("n");
    private static Expr integer(long value) { return JavaExpressions.literal(BigInteger.valueOf(value)); }
    private static Expr operation(NumericOperation operation, Expr... args) { return JavaExpressions.operation(NumericKind.BIG_INTEGER, operation, args); }
    private static Expr pow(Expr exponent) { return operation(NumericOperation.MOD_POW, A, exponent, N); }
    private static Expr product(Expr left, Expr right) { return operation(NumericOperation.MOD_MULTIPLY, left, right, N); }
    private static JointComputationPlan plan(Expr... outputs) {
        var bindings = new ArrayList<Output>();
        for (int i=0;i<outputs.length;i++) bindings.add(new Output("out"+i, NumericKind.BIG_INTEGER.type(), outputs[i]));
        return new JointComputationPlan(Map.of("a", NumericKind.BIG_INTEGER.type(), "x", NumericKind.BIG_INTEGER.type(), "n", NumericKind.BIG_INTEGER.type()), Map.of(), bindings);
    }
    private static Set<SemanticAssumption> assumptions(boolean normalized) {
        var assumptions = new HashSet<SemanticAssumption>();
        for (var name:List.of("a","x","n")) {
            assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, name, "", "captured exact nonnull BigInteger"));
            assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, name, "64", "proven captured magnitude"));
        }
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.NON_NEGATIVE, "x", "", "proven exponent"));
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.POSITIVE, "n", "", "proven modulus"));
        if (normalized) assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.NORMALIZED_MODULAR_INPUT, "a", "n", "0<=a<n"));
        return assumptions;
    }
    private static OptimizationRequest request(JointComputationPlan plan, Set<SemanticAssumption> assumptions) {
        return new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(NumericKind.BIG_INTEGER),
            ComputationOptimizer.SEMANTICS_REVISION, assumptions, SafetyProfile.PRESERVE_JAVA,
            OptimizationGoal.LOWER_ESTIMATED_RUNTIME, OptimizationBudget.DEFAULT, CheckedPolicy.NONE);
    }

    @Test void reusesAvailableModularResultAcrossOutputsWithIndependentProof() {
        var first = operation(NumericOperation.ADD, X, integer(1));
        var second = operation(NumericOperation.ADD, operation(NumericOperation.MULTIPLY, integer(2), X), integer(1));
        var source = plan(pow(first), pow(second));
        var request = request(source, assumptions(true));
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, optimizer.optimize(request, CancellationToken.NONE));
        assertEquals(1, candidate.prepared().nodes().stream().filter(node ->
            JavaExpressions.operationOf(node.expression()).orElse(null)==NumericOperation.MOD_POW).count());
        assertTrue(candidate.cost().estimatedRuntimeImprovement());
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        for (int exponent:List.of(0,1,5,19)) {
            var inputs = Map.<String,Object>of("a", BigInteger.valueOf(3), "x", BigInteger.valueOf(exponent), "n", BigInteger.valueOf(101));
            assertEquals(ComputationOptimizer.prepare(source).execute(inputs), candidate.prepared().execute(inputs));
        }
    }

    @Test void independentlyRechecksOrdinaryMultiplyThenModEmittedFromFusedModularProduct() {
        var source = plan(product(pow(X), A));
        var emitted = source.withOutputs(List.of(operation(NumericOperation.MOD, operation(NumericOperation.MULTIPLY, pow(X), A), N)));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.verify(request(source, assumptions(false)), emitted, CancellationToken.NONE));
        var unnormalized = source.withOutputs(List.of(operation(NumericOperation.MULTIPLY, pow(X), A)));
        assertFalse(optimizer.verify(request(source, assumptions(false)), unnormalized, CancellationToken.NONE) instanceof VerificationResult.Verified);
    }

    @Test void unitExponentCannotRemoveNormalizationWithoutPremise() {
        var source = plan(pow(integer(1)));
        assertFalse(optimizer.verify(request(source, assumptions(false)), source.withOutputs(List.of(A)), CancellationToken.NONE) instanceof VerificationResult.Verified);
        assertInstanceOf(VerificationResult.Verified.class, optimizer.verify(request(source, assumptions(true)), source.withOutputs(List.of(A)), CancellationToken.NONE));
    }

    @Test void exponentZeroAndModulusOneDoNotBecomeUnreducedOne() {
        var source = plan(pow(integer(0)));
        assertFalse(optimizer.verify(request(source, assumptions(true)), source.withOutputs(List.of(integer(1))), CancellationToken.NONE) instanceof VerificationResult.Verified);
        var values = Map.<String,Object>of("a", BigInteger.ZERO, "x", BigInteger.ZERO, "n", BigInteger.ONE);
        assertEquals(BigInteger.ZERO, ComputationOptimizer.prepare(source).execute(values).get("out0"));
    }

    @Test void missingPositiveModulusAndNegativeExponentFailClosed() {
        var source = plan(pow(X));
        var assumptions = assumptions(false);
        assumptions.removeIf(a -> a.kind()==SemanticAssumption.Kind.POSITIVE);
        assertInstanceOf(OptimizationResult.Unsupported.class, optimizer.optimize(request(source, assumptions), CancellationToken.NONE));
        assertInstanceOf(OptimizationResult.Unsupported.class, optimizer.optimize(request(plan(pow(integer(-1))), assumptions(false)), CancellationToken.NONE));
    }

    @Test void bigIntegerOnlyAcceptsAlreadyCapturedIntExponentWithoutEnablingPrimitiveOperations() {
        var expression = operation(NumericOperation.POW, A, new VariableExpr("exponent"));
        var source = new JointComputationPlan(Map.of("a", NumericKind.BIG_INTEGER.type(), "exponent", NumericKind.INT.type()), Map.of(),
            List.of(new Output("out", NumericKind.BIG_INTEGER.type(), operation(NumericOperation.SUBTRACT, expression, expression))));
        var assumptions = new HashSet<SemanticAssumption>();
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS,"a","","exact receiver"));
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND,"a","64","bounded receiver"));
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.NON_NEGATIVE_UPPER_BOUND,"exponent","100","bounded scalar"));
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, optimizer.optimize(request(source, assumptions), CancellationToken.NONE));
        assertEquals(BigInteger.ZERO, candidate.prepared().execute(Map.of("a",BigInteger.TWO,"exponent",3)).get("out"));
        var intArithmetic = JavaExpressions.operation(NumericKind.INT,NumericOperation.ADD,new VariableExpr("exponent"),JavaExpressions.literal(0));
        var excluded = source.withOutputs(List.of(operation(NumericOperation.POW,A,intArithmetic)));
        assertInstanceOf(OptimizationResult.Unsupported.class,optimizer.optimize(request(excluded,assumptions),CancellationToken.NONE));
    }

    @Test void divisionAndModAreNotInterchangeableAndDispatchContractIsMandatory() {
        var source = plan(operation(NumericOperation.DIVIDE, A, integer(3)));
        var wrong = source.withOutputs(List.of(operation(NumericOperation.MOD,A,integer(3))));
        assertFalse(optimizer.verify(request(source,assumptions(false)),wrong,CancellationToken.NONE) instanceof VerificationResult.Verified);
        var assumptions = assumptions(false);
        assumptions.removeIf(a -> a.kind()==SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS);
        assertInstanceOf(OptimizationResult.Unsupported.class,optimizer.optimize(request(source,assumptions),CancellationToken.NONE));
    }
}
