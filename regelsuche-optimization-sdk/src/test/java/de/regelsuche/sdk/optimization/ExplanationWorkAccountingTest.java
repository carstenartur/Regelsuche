package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ExplanationWorkAccountingTest {
    @Test void totalIncludesIndependentVerificationAndPresentation() {
        var fixture = ComputationExplanationsTest.fixture();
        var meter = new VerificationWork(fixture.request(), CancellationToken.NONE);
        assertInstanceOf(VerificationResult.Verified.class,
                new ComputationOptimizer().reverifyWithin(fixture.request(), fixture.candidate(), meter));
        var result = ComputationExplanations.describe(fixture.request(), fixture.candidate(), CancellationToken.NONE);
        assertEquals(meter.used() + result.explanation().orElseThrow().presentationWork(), result.work());
        assertTrue(result.work() > result.explanation().orElseThrow().presentationWork());
    }

    @Test void remainingAllowanceDoesNotChangeTheRecordedSearchConfiguration() {
        var fixture = ComputationExplanationsTest.fixture();
        var full = ComputationExplanations.describe(fixture.request(), fixture.candidate(), CancellationToken.NONE);
        var exact = ComputationExplanations.describe(fixture.request(), fixture.candidate(), full.work(), CancellationToken.NONE);
        assertInstanceOf(VerificationResult.Verified.class, exact.verification());
        assertEquals(full.work(), exact.work());
        assertEquals(fixture.candidate().derivation(), exact.explanation().orElseThrow().derivation());
        var limited = ComputationExplanations.describe(fixture.request(), fixture.candidate(), full.work() - 1, CancellationToken.NONE);
        assertInstanceOf(VerificationResult.BudgetExceeded.class, limited.verification());
        assertTrue(limited.explanation().isEmpty());
        assertTrue(limited.work() > 0);
    }

    @Test void exhaustedAndCancelledCallsNeverReturnAnExplanation() {
        var fixture = ComputationExplanationsTest.fixture();
        var exhausted = ComputationExplanations.describe(fixture.request(), fixture.candidate(), 0, CancellationToken.NONE);
        assertInstanceOf(VerificationResult.BudgetExceeded.class, exhausted.verification());
        assertTrue(exhausted.explanation().isEmpty());
        var cancelled = ComputationExplanations.describe(fixture.request(), fixture.candidate(), () -> true);
        assertInstanceOf(VerificationResult.Cancelled.class, cancelled.verification());
        assertEquals(0, cancelled.work());
    }

    @Test void aCompletedPreparationIsDebitedEvenWhenItExceedsTheRemainingAllowance() {
        var fixture = ComputationExplanationsTest.fixture();
        var meter = new VerificationWork(fixture.request(), CancellationToken.NONE);
        assertInstanceOf(VerificationResult.Verified.class,
                new ComputationOptimizer().reverifyWithin(fixture.request(), fixture.candidate(), meter));
        long preparation = ComputationOptimizer.prepare(fixture.request().plan()).cost().inspectionWork()
                + ComputationOptimizer.prepare(fixture.candidate().plan()).cost().inspectionWork();
        var result = ComputationExplanations.describe(fixture.request(), fixture.candidate(), meter.used() + preparation, CancellationToken.NONE);
        assertInstanceOf(VerificationResult.BudgetExceeded.class, result.verification());
        assertEquals(meter.used() + 1 + preparation, result.work());
    }

    @Test void rejectedCandidatesAlsoPayForCounterexamplePreparation() {
        var x = new de.regelsuche.ast.VariableExpr("x");
        var source = new de.regelsuche.search.program.JointComputationPlan(java.util.Map.of("x", NumericKind.INT.type()), java.util.Map.of(),
                java.util.List.of(new de.regelsuche.search.program.JointComputationPlan.Output("out", NumericKind.INT.type(),
                        JavaExpressions.operation(NumericKind.INT, NumericOperation.SUBTRACT, x, x))));
        var request = new OptimizationRequest(source, SourceEvaluationTrace.fromPlan(source), java.util.Set.of(NumericKind.INT),
                ComputationOptimizer.SEMANTICS_REVISION, java.util.Set.of(), SafetyProfile.PRESERVE_JAVA, OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
                OptimizationBudget.DEFAULT, CheckedPolicy.NONE);
        var valid = assertInstanceOf(OptimizationResult.Candidate.class, new ComputationOptimizer().optimize(request, CancellationToken.NONE));
        var wrong = source.withOutputs(java.util.List.of(JavaExpressions.literal(1)));
        var prepared = ComputationOptimizer.prepare(wrong);
        var forged = new OptimizationResult.Candidate(wrong, prepared, valid.evidence(), valid.obligations(), valid.cost(), valid.searchCompletion(), 0);
        var direct = new VerificationWork(request, CancellationToken.NONE);
        var checker = new SemanticChecker(request, direct); checker.validate(); assertFalse(checker.check(source, wrong).accepted());
        var actual = new VerificationWork(request, CancellationToken.NONE);
        assertInstanceOf(VerificationResult.Refuted.class, new ComputationOptimizer().reverifyWithin(request, forged, actual));
        assertTrue(actual.used() >= direct.used() + ComputationOptimizer.prepare(source).cost().inspectionWork() + prepared.cost().inspectionWork());
    }
}
