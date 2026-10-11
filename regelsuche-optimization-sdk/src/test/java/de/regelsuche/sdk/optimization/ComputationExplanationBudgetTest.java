package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Public API regressions: neither phase may reset the caller's allowance. */
class ComputationExplanationBudgetTest {
    @Test void separatelyAffordablePhasesMustNotExceedTheirCombinedAllowance() {
        var fixture = ComputationExplanationsTest.fixture();
        var full = ComputationExplanations.describe(fixture.request(), fixture.candidate(), CancellationToken.NONE);
        long presentation = full.explanation().orElseThrow().presentationWork();
        long verification = minimumVerificationWork(fixture);
        assertTrue(presentation > 1);
        long limit = Math.addExact(verification, presentation) - 1;
        var request = withWork(fixture.request(), limit);
        assertInstanceOf(VerificationResult.Verified.class,
                new ComputationOptimizer().reverify(request, fixture.candidate(), CancellationToken.NONE),
                "The proof alone must fit: this tests the combined operation, not early rejection");
        var result = ComputationExplanations.describe(request, fixture.candidate(), CancellationToken.NONE);
        var exceeded = assertInstanceOf(VerificationResult.BudgetExceeded.class, result.verification(),
                "Proof and rendering separately fit, but together exceed maximumWork");
        assertTrue(exceeded.work() <= limit);
        assertTrue(result.explanation().isEmpty(), "Budget failure must not publish a partial explanation");
    }

    @Test void exactCombinedAllowanceSucceedsWithoutChangingThePresentationCost() {
        var fixture = ComputationExplanationsTest.fixture();
        var full = ComputationExplanations.describe(fixture.request(), fixture.candidate(), CancellationToken.NONE)
                .explanation().orElseThrow();
        long limit = Math.addExact(minimumVerificationWork(fixture), full.presentationWork());
        var result = ComputationExplanations.describe(withWork(fixture.request(), limit), fixture.candidate(), CancellationToken.NONE);
        assertInstanceOf(VerificationResult.Verified.class, result.verification());
        var explanation = result.explanation().orElseThrow();
        assertEquals(full.presentationWork(), explanation.presentationWork());
        assertEquals(full.proof(), explanation.proof());
        assertEquals(full.original(), explanation.original());
        assertEquals(full.replacement(), explanation.replacement());
    }

    @Test void cancellationAtThePhaseBoundaryDoesNotPublishAnExplanation() {
        var fixture = ComputationExplanationsTest.fixture();
        AtomicInteger checkpoints = new AtomicInteger();
        assertInstanceOf(VerificationResult.Verified.class, new ComputationOptimizer().reverify(
                fixture.request(), fixture.candidate(), () -> { checkpoints.incrementAndGet(); return false; }));
        int proofCheckpoints = checkpoints.get();
        checkpoints.set(0);
        var result = ComputationExplanations.describe(fixture.request(), fixture.candidate(),
                () -> checkpoints.incrementAndGet() > proofCheckpoints);
        assertInstanceOf(VerificationResult.Cancelled.class, result.verification());
        assertTrue(result.explanation().isEmpty());
    }

    private static long minimumVerificationWork(ComputationExplanationsTest.Fixture fixture) {
        var optimizer = new ComputationOptimizer();
        long low = 1, high = fixture.request().budget().maximumWork();
        assertInstanceOf(VerificationResult.Verified.class,
                optimizer.reverify(withWork(fixture.request(), high), fixture.candidate(), CancellationToken.NONE));
        while (low < high) {
            long middle = low + (high - low) / 2;
            var result = optimizer.reverify(withWork(fixture.request(), middle), fixture.candidate(), CancellationToken.NONE);
            if (result instanceof VerificationResult.Verified) high = middle;
            else {
                assertInstanceOf(VerificationResult.BudgetExceeded.class, result);
                low = middle + 1;
            }
        }
        return low;
    }

    private static OptimizationRequest withWork(OptimizationRequest original, long maximum) {
        var budget = original.budget();
        return new OptimizationRequest(original.plan(), original.sourceTrace(), original.selectedKinds(),
                original.semanticsRevision(), original.assumptions(), original.safetyProfile(), original.goal(),
                new OptimizationBudget(maximum, budget.maximumStates(), budget.maximumCandidates(), budget.timeoutMillis()),
                original.checkedPolicy());
    }
}
