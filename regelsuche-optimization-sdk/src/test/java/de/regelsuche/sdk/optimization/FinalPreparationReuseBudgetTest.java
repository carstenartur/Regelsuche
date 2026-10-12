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

        // Temporary characterization only: smaller budgets also change exploration.
        // Record a real final-preparation failure, then freeze its allowance before the fix.
        long duplicatePreparation = reference.cost().sourceCost().inspectionWork()
                + reference.cost().candidateCost().inspectionWork();
        long emptyDerivationWork = 2 + referenceRequest.plan().searchExpression().work()
                + reference.plan().searchExpression().work();
        long allowance = -1;
        var observed = new java.util.TreeMap<String, Integer>();
        for (long limit = 256; limit <= reference.work(); limit++) {
            var result = optimizer.optimize(request(limit), CancellationToken.NONE);
            String outcome = result instanceof OptimizationResult.BudgetExceeded exceeded
                    ? exceeded.diagnostic() : result.getClass().getSimpleName();
            observed.merge(outcome, 1, Integer::sum);
            if (result instanceof OptimizationResult.BudgetExceeded exceeded
                    && exceeded.diagnostic().equals("FINAL_PREPARATION_BUDGET_EXCEEDED")
                    && exceeded.work() - duplicatePreparation + emptyDerivationWork <= limit) {
                allowance = limit;
                System.out.printf("FINAL_PREPARATION_REUSE_RED allowance=%d exceededWork=%d "
                        + "duplicatePreparation=%d emptyDerivationWork=%d outcomes=%s%n",
                        allowance, exceeded.work(), duplicatePreparation, emptyDerivationWork, observed);
                break;
            }
        }
        assertTrue(allowance > 0, "No final-preparation counterexample observed: " + observed);

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
