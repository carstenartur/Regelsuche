package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SearchDerivationExplanationTest {
    @Test void explanationCarriesExactlyTheSelectedImmutableSearchPath() {
        var f = SearchDerivationTest.fixture(NumericKind.INT);
        var result = ComputationExplanations.describe(f.request(), f.candidate(), CancellationToken.NONE);
        assertInstanceOf(VerificationResult.Verified.class, result.verification());
        assertEquals(f.candidate().derivation(), result.explanation().orElseThrow().derivation());
        assertFalse(result.explanation().orElseThrow().derivation().orElseThrow().steps().isEmpty());
    }
    @Test void modularMultiOutputPathIsRetainedWithoutASourceSpecificRecognizer() {
        var f = ComputationExplanationsTest.fixture();
        var result = ComputationExplanations.describe(f.request(), f.candidate(), CancellationToken.NONE);
        assertInstanceOf(VerificationResult.Verified.class, result.verification(), result.toString());
        assertEquals(f.candidate().derivation(), result.explanation().orElseThrow().derivation());
        var path = result.explanation().orElseThrow().derivation().orElseThrow();
        assertFalse(path.steps().isEmpty());
        assertEquals(f.request().plan().expression(), path.steps().getFirst().before());
        assertEquals(f.candidate().plan().expression(), path.steps().getLast().after());
        System.out.println("ACTUAL_MODULAR_SEARCH_PATH " + path.steps());
    }
    @Test void absentHistoryRemainsExplicitlyAbsentInPresentation() {
        var f = SearchDerivationTest.fixture(NumericKind.INT);
        var result = ComputationExplanations.describe(f.request(), legacy(f.candidate()), CancellationToken.NONE);
        assertTrue(result.explanation().orElseThrow().derivation().isEmpty());
    }
    @Test void pathReplaySharesTheProofBudgetRatherThanReceivingAFreshAllowance() {
        var f = SearchDerivationTest.fixture(NumericKind.INT);
        var old = legacy(f.candidate());
        var work = new VerificationWork(f.request(), CancellationToken.NONE);
        assertInstanceOf(VerificationResult.Verified.class, new ComputationOptimizer().reverifyWithin(f.request(), old, work));
        var r = f.request();
        var bounded = new OptimizationRequest(r.plan(), r.sourceTrace(), r.selectedKinds(), r.semanticsRevision(),
                r.assumptions(), r.safetyProfile(), r.goal(), new OptimizationBudget(work.used(), 20_000, 64, 5000), r.checkedPolicy());
        var result = ComputationExplanations.describe(bounded, f.candidate(), CancellationToken.NONE);
        assertInstanceOf(VerificationResult.BudgetExceeded.class, result.verification());
        assertTrue(result.explanation().isEmpty());
    }
    @Test void cancellationDuringPathReplayDoesNotPublishTheAlreadyVerifiedEndpoints() {
        var f = SearchDerivationTest.fixture(NumericKind.INT);
        AtomicInteger count = new AtomicInteger();
        assertInstanceOf(VerificationResult.Verified.class, new ComputationOptimizer().reverify(f.request(), legacy(f.candidate()), () -> { count.incrementAndGet(); return false; }));
        int finalProofCalls = count.get(); count.set(0);
        var result = ComputationExplanations.describe(f.request(), f.candidate(), () -> count.incrementAndGet() > finalProofCalls + 2);
        assertInstanceOf(VerificationResult.Cancelled.class, result.verification());
        assertTrue(result.explanation().isEmpty());
    }
    @Test void callerAllowanceDoesNotChangeTheRecordedGenerationConfiguration() {
        var f = ComputationExplanationsTest.fixture();
        var r = f.request();
        long previous = -1;
        for (long limit : new long[]{r.budget().maximumWork(), 2 * r.budget().maximumWork()}) {
            var changed = new OptimizationRequest(r.plan(), r.sourceTrace(), r.selectedKinds(), r.semanticsRevision(),
                    r.assumptions(), r.safetyProfile(), r.goal(), new OptimizationBudget(limit, 20_000, 64, 5000), r.checkedPolicy());
            var work = new VerificationWork(changed, CancellationToken.NONE);
            assertInstanceOf(VerificationResult.Verified.class, new ComputationOptimizer().reverifyWithin(changed, f.candidate(), work));
            if (previous >= 0) assertEquals(previous, work.used());
            previous = work.used();
        }
    }
    private static OptimizationResult.Candidate legacy(OptimizationResult.Candidate c) {
        return new OptimizationResult.Candidate(c.plan(), c.prepared(), c.evidence(), c.obligations(), c.cost(), c.searchCompletion(), c.work(), Optional.empty());
    }
}
