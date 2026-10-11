package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.JointComputationPlan.Output;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModularReductionOptimizationTest {
    private final ComputationOptimizer optimizer = new ComputationOptimizer();
    private static final Expr X = new VariableExpr("x"), N = new VariableExpr("n");

    @Test void removesIntermediateReductionsBeforeAnAddendAtLowerRuntimeCost() {
        var request = request(384, 4096);

        var candidate = assertInstanceOf(OptimizationResult.Candidate.class,
            optimizer.optimize(request, CancellationToken.NONE));

        assertTrue(candidate.cost().estimatedRuntimeImprovement(), candidate.cost().toString());
        assertTrue(candidate.cost().candidateCost().operationWork() + 7 < candidate.cost().sourceCost().operationWork(),
            "Removing both reductions must leave room for the adapter's existing modulus receiver check");
        assertTrue(candidate.cost().candidateScore() + 70 < candidate.cost().sourceScore(), candidate.cost().toString());
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));

        // Hand-derived x^3+4 residues; no source evaluator or generator supplies the oracle.
        long[][] examples = {{-3, 7, 5}, {0, 7, 4}, {3, 7, 3}, {2, 8, 4}, {5, 1, 0}};
        for (long[] example : examples) {
            var inputs = Map.<String, Object>of("x", BigInteger.valueOf(example[0]), "n", BigInteger.valueOf(example[1]));
            assertEquals(BigInteger.valueOf(example[2]), candidate.prepared().execute(inputs).get("result"));
        }
    }

    @Test void independentlyVerifiesTheOrdinaryJavaOperationsWithoutGeneratorEvidence() {
        var request = request(384, 4096);
        var target = request.plan().withOutputs(List.of(operation(NumericOperation.MOD, unreducedCubePlusFour(), N)));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.verify(request, target, CancellationToken.NONE));
    }

    @Test void preservingFinalReductionDoesNotPermitUnsafeIntermediateMagnitude() {
        // Each original product stays below 1,000,000 bits after reduction to a 64-bit modulus.
        // The proposed unreduced cube would exceed that same unchanged SDK range bound.
        var request = request(400_000, 64);
        var target = request.plan().withOutputs(List.of(operation(NumericOperation.MOD, unreducedCubePlusFour(), N)));
        var rejected = assertInstanceOf(VerificationResult.Unsupported.class,
            optimizer.verify(request, target, CancellationToken.NONE));
        assertEquals("BIG_INTEGER_SUPPORTED_RANGE_NOT_PROVED", rejected.diagnostic());
    }

    @Test void noPositiveModulusContractCannotAdmitTheArithmeticRewrite() {
        var request = request(384, 4096);
        var assumptions = new HashSet<>(request.assumptions());
        assumptions.removeIf(assumption -> assumption.kind() == SemanticAssumption.Kind.POSITIVE);
        var missingPremise = new OptimizationRequest(request.plan(), request.sourceTrace(), request.selectedKinds(),
            request.semanticsRevision(), assumptions, request.safetyProfile(), request.goal(), request.budget(), request.checkedPolicy());
        assertInstanceOf(OptimizationResult.Unsupported.class, optimizer.optimize(missingPremise, CancellationToken.NONE));
    }

    private static OptimizationRequest request(long xBits, long nBits) {
        Expr square = operation(NumericOperation.MOD, operation(NumericOperation.MULTIPLY, X, X), N);
        Expr cube = operation(NumericOperation.MOD, operation(NumericOperation.MULTIPLY, square, X), N);
        Expr result = operation(NumericOperation.MOD, operation(NumericOperation.ADD, cube, integer(4)), N);
        var plan = new JointComputationPlan(Map.of("x", NumericKind.BIG_INTEGER.type(), "n", NumericKind.BIG_INTEGER.type()), Map.of(),
            List.of(new Output("result", NumericKind.BIG_INTEGER.type(), result)));
        var assumptions = Set.of(
            new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, "x", "", "captured exact nonnull BigInteger"),
            new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, "n", "", "captured exact nonnull BigInteger"),
            new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, "x", Long.toString(xBits), "captured magnitude"),
            new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, "n", Long.toString(nBits), "captured magnitude"),
            new SemanticAssumption(SemanticAssumption.Kind.POSITIVE, "n", "", "positive modulus"));
        return new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(NumericKind.BIG_INTEGER),
            ComputationOptimizer.SEMANTICS_REVISION, assumptions, SafetyProfile.PRESERVE_JAVA,
            OptimizationGoal.LOWER_ESTIMATED_RUNTIME, OptimizationBudget.DEFAULT, CheckedPolicy.NONE);
    }

    private static Expr unreducedCubePlusFour() {
        Expr cube = operation(NumericOperation.MULTIPLY, operation(NumericOperation.MULTIPLY, X, X), X);
        return operation(NumericOperation.ADD, cube, integer(4));
    }
    private static Expr integer(long value) { return JavaExpressions.literal(BigInteger.valueOf(value)); }
    private static Expr operation(NumericOperation operation, Expr... operands) {
        return JavaExpressions.operation(NumericKind.BIG_INTEGER, operation, operands);
    }
}
