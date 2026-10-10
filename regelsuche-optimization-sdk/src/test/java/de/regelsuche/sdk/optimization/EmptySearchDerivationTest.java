package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A scheduling improvement must not acquire a fictitious expression rewrite. */
class EmptySearchDerivationTest {
    @Test void optimizerPreservesAPresentZeroEdgePathForSharedEvaluations() {
        var product = JavaExpressions.operation(NumericKind.INT, NumericOperation.MULTIPLY,
                new VariableExpr("x"), new VariableExpr("y"));
        var plan = new JointComputationPlan(Map.of("x", NumericKind.INT.type(), "y", NumericKind.INT.type()), Map.of(),
                List.of(new JointComputationPlan.Output("left", NumericKind.INT.type(), product),
                        new JointComputationPlan.Output("right", NumericKind.INT.type(), product)));
        // The original Java evaluated x*y twice; the prepared DAG evaluates it once.
        var trace = new SourceEvaluationTrace(List.of(
                new SourceEvaluationTrace.Occurrence("first", product, NumericKind.INT, NumericKind.INT),
                new SourceEvaluationTrace.Occurrence("second", product, NumericKind.INT, NumericKind.INT)));
        var request = new OptimizationRequest(plan, trace, Set.of(NumericKind.INT), ComputationOptimizer.SEMANTICS_REVISION,
                Set.of(), SafetyProfile.PRESERVE_JAVA, OptimizationGoal.READABILITY,
                new OptimizationBudget(2_000_000, 20_000, 64, 5000), CheckedPolicy.NONE);
        var optimizer = new ComputationOptimizer();
        var result = optimizer.optimize(request, CancellationToken.NONE);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, result, result.toString());
        assertEquals(plan.expression(), candidate.plan().expression());
        assertTrue(candidate.cost().estimatedRuntimeImprovement());
        assertTrue(candidate.derivation().isPresent());
        assertTrue(candidate.derivation().orElseThrow().steps().isEmpty());
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        var explanation = ComputationExplanations.describe(request, candidate, CancellationToken.NONE);
        assertInstanceOf(VerificationResult.Verified.class, explanation.verification());
        var presented = explanation.explanation().orElseThrow().derivation();
        assertTrue(presented.isPresent(), "Recorded zero edges must not collapse to absent legacy history");
        assertEquals(candidate.derivation(), presented);
        assertTrue(presented.orElseThrow().steps().isEmpty());
        assertEquals(Map.of("left", -2, "right", -2), candidate.prepared().execute(Map.of("x", Integer.MAX_VALUE, "y", 2)));
    }
}
