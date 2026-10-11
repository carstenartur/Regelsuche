package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FinalVerificationBudgetTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void spentWorkBeyondTheAllowanceDominatesEitherProofOutcome(boolean proofAccepted) {
        var outcome = ComputationOptimizer.finalVerificationOutcome(proofAccepted, 101, 100).orElseThrow();
        var exceeded = assertInstanceOf(OptimizationResult.BudgetExceeded.class, outcome);
        assertEquals("FINAL_VERIFICATION_BUDGET_EXCEEDED", exceeded.diagnostic());
        assertEquals(101, exceeded.work());
    }

    @Test void unprovedAtTheAllowanceRemainsInconclusive() {
        var outcome = ComputationOptimizer.finalVerificationOutcome(false, 100, 100).orElseThrow();
        var inconclusive = assertInstanceOf(OptimizationResult.Inconclusive.class, outcome);
        assertEquals("FINAL_INDEPENDENT_CHECK_NOT_PROVED", inconclusive.diagnostic());
        assertEquals(100, inconclusive.work());
    }

    @Test void provedAtTheAllowanceCanContinueToPreparation() {
        assertTrue(ComputationOptimizer.finalVerificationOutcome(true, 100, 100).isEmpty());
    }
}
