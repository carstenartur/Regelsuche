package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A completed proof must be able to reuse costs already obtained in the same request. */
class FinalPreparationReuseBudgetTest {
    @Test void sharedEvaluationsFinishWithinTheAllowanceForOneTrustedFinalPreparation() {
        var optimizer = new ComputationOptimizer();
        var referenceRequest = request(2_000_000);
        var referenceResult = optimizer.optimize(referenceRequest, CancellationToken.NONE);
        var reference = assertInstanceOf(OptimizationResult.Candidate.class, referenceResult,
                referenceResult.toString());
        assertEquals(referenceRequest.plan().expression(), reference.plan().expression());
        assertTrue(reference.derivation().isPresent());
        assertTrue(reference.derivation().orElseThrow().steps().isEmpty());

        // The shared v7 inventory pays for an additional core rule, even on a non-hit.
        // Keep below the historical 1,200 no-reuse baseline; do not hide the old
        // 1,092 overrun, which is retained separately below.
        long allowance = 1_120;

        var constrainedRequest = request(allowance);
        var constrained = optimizer.optimize(constrainedRequest, CancellationToken.NONE);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, constrained,
                "Fixed request allowance " + allowance + ": " + constrained);
        assertTrue(candidate.work() <= allowance);
        assertEquals(reference.plan().expression(), candidate.plan().expression());
        assertEquals(reference.cost(), candidate.cost());
        assertEquals(reference.evidence(), candidate.evidence());
        assertTrue(candidate.cost().estimatedRuntimeImprovement());
        assertTrue(candidate.derivation().isPresent());
        assertTrue(candidate.derivation().orElseThrow().steps().isEmpty());
        assertInstanceOf(VerificationResult.Verified.class,
                optimizer.reverify(constrainedRequest, candidate, CancellationToken.NONE));
        assertEquals(Map.of("left", -2, "right", -2),
                candidate.prepared().execute(Map.of("x", Integer.MAX_VALUE, "y", 2)));
    }

    @Test void additionalInventoryWorkIsNotRefundedAtTheHistoricalTightLimit() {
        long historicalAllowance = 1_092;
        var exhausted = assertInstanceOf(OptimizationResult.BudgetExceeded.class,
                new ComputationOptimizer().optimize(request(historicalAllowance), CancellationToken.NONE));
        assertTrue(exhausted.work() > historicalAllowance);
        assertEquals("DERIVATION_CAPTURE_BUDGET_EXCEEDED", exhausted.diagnostic());
    }

    private static OptimizationRequest request(long maximumWork) {
        var product = JavaExpressions.operation(NumericKind.INT, NumericOperation.MULTIPLY,
                new VariableExpr("x"), new VariableExpr("y"));
        var plan = new JointComputationPlan(
                Map.of("x", NumericKind.INT.type(), "y", NumericKind.INT.type()), Map.of(),
                List.of(new JointComputationPlan.Output("left", NumericKind.INT.type(), product),
                        new JointComputationPlan.Output("right", NumericKind.INT.type(), product)));
        var trace = new SourceEvaluationTrace(List.of(
                new SourceEvaluationTrace.Occurrence("first", product, NumericKind.INT, NumericKind.INT),
                new SourceEvaluationTrace.Occurrence("second", product, NumericKind.INT, NumericKind.INT)));
        return new OptimizationRequest(plan, trace, Set.of(NumericKind.INT),
                ComputationOptimizer.SEMANTICS_REVISION, Set.of(), SafetyProfile.PRESERVE_JAVA,
                OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
                new OptimizationBudget(maximumWork, 20_000, 64, 5000), CheckedPolicy.NONE);
    }
}
