package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SmallModularPowerTest {
    @Test void modularSquareUsesTheExistingSearchAndIndependentModularProof() {
        var expression = JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.MOD_POW,
                new VariableExpr("x"), JavaExpressions.literal(BigInteger.TWO), new VariableExpr("m"));
        var source = new JointComputationPlan(Map.of("x", NumericKind.BIG_INTEGER.type(), "m", NumericKind.BIG_INTEGER.type()),
                Map.of(), List.of(new JointComputationPlan.Output("result", NumericKind.BIG_INTEGER.type(), expression)));
        var assumptions = Set.of(
                new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, "x", "", "test contract"),
                new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, "m", "", "test contract"),
                new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, "x", "4096", "test contract"),
                new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, "m", "4096", "test contract"),
                new SemanticAssumption(SemanticAssumption.Kind.POSITIVE, "m", "", "test contract"));
        var request = new OptimizationRequest(source, SourceEvaluationTrace.fromPlan(source), Set.of(NumericKind.BIG_INTEGER),
                ComputationOptimizer.SEMANTICS_REVISION, assumptions, SafetyProfile.PRESERVE_JAVA,
                OptimizationGoal.LOWER_ESTIMATED_RUNTIME, new OptimizationBudget(1000000, 20000, 64, 5000), CheckedPolicy.NONE);
        var optimizer = new ComputationOptimizer();
        var result = assertInstanceOf(OptimizationResult.Candidate.class, optimizer.optimize(request, CancellationToken.NONE));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, result, CancellationToken.NONE));
        assertTrue(result.cost().estimatedRuntimeImprovement());
        assertTrue(result.prepared().nodes().stream().noneMatch(node ->
                JavaExpressions.operationOf(node.expression()).orElse(null) == NumericOperation.MOD_POW));
        for (int x = -30; x <= 30; x++) for (int modulus = 1; modulus <= 31; modulus++) {
            var values = Map.<String, Object>of("x", BigInteger.valueOf(x), "m", BigInteger.valueOf(modulus));
            assertEquals(ComputationOptimizer.prepare(source).execute(values), result.prepared().execute(values));
        }
        var wrong = source.withOutputs(List.of(JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.MULTIPLY,
                new VariableExpr("x"), new VariableExpr("x"))));
        assertFalse(optimizer.verify(request, wrong, CancellationToken.NONE) instanceof VerificationResult.Verified);
    }
}
