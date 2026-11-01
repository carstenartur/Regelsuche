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
    @Test void sharedShiftKeepsItsCandidateWithoutRebuildingTheCapturedSource() {
        var shift = JavaExpressions.operation(NumericKind.INT, NumericOperation.SHIFT_LEFT,
                new VariableExpr("u"), new VariableExpr("distance"));
        var left = JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, new VariableExpr("first"), shift);
        var right = JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, new VariableExpr("second"), shift);
        var plan = new JointComputationPlan(Map.of("u", NumericKind.INT.type(), "distance", NumericKind.INT.type(),
                "first", NumericKind.INT.type(), "second", NumericKind.INT.type()), Map.of(),
                List.of(new JointComputationPlan.Output("left", NumericKind.INT.type(), left),
                        new JointComputationPlan.Output("right", NumericKind.INT.type(), right)));
        var request = new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(NumericKind.INT),
                ComputationOptimizer.SEMANTICS_REVISION, Set.of(), SafetyProfile.PRESERVE_JAVA,
                OptimizationGoal.LOWER_ESTIMATED_RUNTIME, new OptimizationBudget(2200, 20_000, 64, 5000), CheckedPolicy.NONE);
        var optimizer = new ComputationOptimizer();
        var result = optimizer.optimize(request, CancellationToken.NONE);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, result, result.toString());
        assertTrue(candidate.work() <= 2200, "Capture work: " + candidate.work());
        assertTrue(candidate.cost().estimatedRuntimeImprovement());
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
    }

    @Test void noImprovementDoesNotChargeAnUnconstructedCandidate() {
        var plan = new JointComputationPlan(Map.of("x", NumericKind.INT.type()), Map.of(),
                List.of(new JointComputationPlan.Output("result", NumericKind.INT.type(), new VariableExpr("x"))));
        var request = new OptimizationRequest(plan, new SourceEvaluationTrace(List.of()), Set.of(NumericKind.INT),
                ComputationOptimizer.SEMANTICS_REVISION, Set.of(), SafetyProfile.PRESERVE_JAVA,
                OptimizationGoal.LOWER_ESTIMATED_RUNTIME, new OptimizationBudget(100_000, 20_000, 64, 5000), CheckedPolicy.NONE);
        var result = assertInstanceOf(OptimizationResult.NoImprovement.class,
                new ComputationOptimizer().optimize(request, CancellationToken.NONE));
        // This ceiling excludes the unused 15-unit Candidate-constructor
        // reservation; the complete search must account only for executed work.
        assertTrue(result.work() <= 330, result.toString());
    }

    @Test void sharedEvaluationsFinishWithinTheAllowanceForOneTrustedFinalPreparation() {
        var optimizer = new ComputationOptimizer();
        var referenceRequest = request(2_000_000);
        var referenceResult = optimizer.optimize(referenceRequest, CancellationToken.NONE);
        var reference = assertInstanceOf(OptimizationResult.Candidate.class, referenceResult,
                referenceResult.toString());
        assertEquals(referenceRequest.plan().expression(), reference.plan().expression());
        assertTrue(reference.derivation().isPresent());
        assertTrue(reference.derivation().orElseThrow().steps().isEmpty());

        // Canonical resolution avoids repeated structural interning in preparation.
        // Leave less than one extra preparation (84 units)
        // to retain the reuse regression without discounting actual traversal.
        long allowance = 3_100;
        assertTrue(reference.work() + reference.cost().candidateCost().inspectionWork() > allowance);

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

    @Test void sharedNormalizationNowFinishesWithinTheHistoricalTightLimit() {
        long historicalAllowance = 1_092;
        var result = new ComputationOptimizer().optimize(request(historicalAllowance), CancellationToken.NONE);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, result, result.toString());
        assertTrue(candidate.work() <= historicalAllowance);
        assertInstanceOf(VerificationResult.Verified.class,
                new ComputationOptimizer().reverify(request(historicalAllowance), candidate, CancellationToken.NONE));
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
