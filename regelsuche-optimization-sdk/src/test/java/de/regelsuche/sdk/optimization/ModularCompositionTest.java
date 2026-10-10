package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModularCompositionTest {
    private static final Expr X = new VariableExpr("x"), M = new VariableExpr("m");

    @Test void repeatedReductionHasAnIndependentProofAndPreservesSignedValues() {
        check(op(NumericOperation.MOD, op(NumericOperation.MOD, X, M), M),
                op(NumericOperation.MOD, X, M));
    }

    @Test void aSurroundingReductionDoesNotHideTheModularSquare() {
        Expr square = op(NumericOperation.MOD_POW, X, JavaExpressions.literal(BigInteger.TWO), M);
        check(op(NumericOperation.MOD, op(NumericOperation.ADD, square, X), M),
                op(NumericOperation.MOD, op(NumericOperation.ADD, op(NumericOperation.MOD_MULTIPLY, X, X, M), X), M));
    }

    @Test void aComputedBaseCanBeSquaredWithoutLosingItsReduction() {
        Expr base = op(NumericOperation.MOD, op(NumericOperation.ADD, X, JavaExpressions.literal(BigInteger.ONE)), M);
        check(op(NumericOperation.MOD_POW, base, JavaExpressions.literal(BigInteger.TWO), M),
                op(NumericOperation.MOD_MULTIPLY, base, base, M));
    }

    private static void check(Expr expression, Expr equivalent) {
        var source = new JointComputationPlan(Map.of("x", NumericKind.BIG_INTEGER.type(), "m", NumericKind.BIG_INTEGER.type()),
                Map.of(), List.of(new JointComputationPlan.Output("result", NumericKind.BIG_INTEGER.type(), expression)));
        var assumptions = new HashSet<SemanticAssumption>();
        for (String input : source.inputs().keySet()) {
            assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, input, "", "test contract"));
            assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, input, "4096", "test contract"));
        }
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.POSITIVE, "m", "", "test contract"));
        var request = new OptimizationRequest(source, SourceEvaluationTrace.fromPlan(source), Set.of(NumericKind.BIG_INTEGER),
                ComputationOptimizer.SEMANTICS_REVISION, assumptions, SafetyProfile.PRESERVE_JAVA,
                OptimizationGoal.LOWER_ESTIMATED_RUNTIME, new OptimizationBudget(1000000, 20000, 64, 5000), CheckedPolicy.NONE);
        var optimizer = new ComputationOptimizer();
        assertInstanceOf(VerificationResult.Verified.class, optimizer.verify(request, source.withOutputs(List.of(equivalent)), CancellationToken.NONE));
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, optimizer.optimize(request, CancellationToken.NONE));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        assertTrue(candidate.cost().estimatedRuntimeImprovement());
        for (int x = -30; x <= 30; x++) for (int m = 1; m <= 31; m++) {
            var inputs = Map.<String, Object>of("x", BigInteger.valueOf(x), "m", BigInteger.valueOf(m));
            assertEquals(ComputationOptimizer.prepare(source).execute(inputs), candidate.prepared().execute(inputs));
        }
        assertFalse(optimizer.verify(request, source.withOutputs(List.of(X)), CancellationToken.NONE) instanceof VerificationResult.Verified);
    }
    private static Expr op(NumericOperation operation, Expr... arguments) {
        return JavaExpressions.operation(NumericKind.BIG_INTEGER, operation, arguments);
    }
}
