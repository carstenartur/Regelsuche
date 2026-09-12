package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Fixture controls only: the preregistered comparison corpus is never a unit-test training set. */
@Timeout(180)
class ExternalPolynomialComparisonWorkerTest {
    private static ExternalPolynomialComparisonWorker worker;
    @BeforeAll static void prepare() { worker = new ExternalPolynomialComparisonWorker(); worker.initialize(); }
    @Test void countsSurfaceArithmeticWithoutSimplifying() {
        assertEquals(6, ExternalPolynomialComparisonWorker.cost("(x+y)*(x-y)+y*y+2"));
        assertEquals(3, ExternalPolynomialComparisonWorker.cost("x/3+x/6"));
        assertEquals(1, ExternalPolynomialComparisonWorker.cost("-5"));
        assertEquals(0, ExternalPolynomialComparisonWorker.cost("17"));
        assertThrows(IllegalArgumentException.class, () -> ExternalPolynomialComparisonWorker.cost("sin(x)"));
    }
    @Test void sourceOnlySearchRetainsAReplayedIncumbentWithoutInventingTargetSuccess() {
        var result = worker.run("BASE", "(z+0)*1+101");
        assertEquals("CANDIDATE", result.get("status"));
        assertEquals("", result.get("target"));
        assertFalse((Boolean) result.get("targetReached"));
        assertTrue((Integer) result.get("outputCost") < (Integer) result.get("inputCost"));
        assertFalse(((List<?>) result.get("witness")).isEmpty());
        assertTrue((Long) result.get("selectedReplayWork") > 0);
    }
    @Test void noOpRetainsZeroReplayCostAndNoProofClaims() {
        var result = worker.run("BASE", "103");
        assertEquals("103", result.get("output"));
        assertTrue(((List<?>) result.get("witness")).isEmpty());
        assertEquals(0L, result.get("selectedReplayWork"));
    }
    @Test void rejectsUnknownProfilesAndTrainingOverlap() {
        assertThrows(IllegalArgumentException.class, () -> worker.run("NOT_A_PROFILE", "x+2"));
        assertThrows(IllegalArgumentException.class, () -> worker.run("BASE", TraceStrategyTransferExample.trainingInputs().getFirst().expression()));
        assertThrows(IllegalArgumentException.class, () -> worker.run("BASE", "ln(x)"));
    }
    @Test void repeatedQueriesHaveIdenticalCanonicalSearchEvidenceAndFrozenKnowledge() {
        var first = worker.run("LEARNED_RANKED", "(z+0)*1+107");
        var second = worker.run("LEARNED_RANKED", "(z+0)*1+107");
        assertEquals(first.get("modelHash"), second.get("modelHash"));
        assertEquals(first.get("search"), second.get("search"));
        assertEquals(first.get("output"), second.get("output"));
        assertEquals(first.get("selectedReplayWork"), second.get("selectedReplayWork"));
    }
}
