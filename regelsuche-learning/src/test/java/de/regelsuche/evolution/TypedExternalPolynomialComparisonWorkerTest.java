package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Development fixtures only; never opens the frozen external corpus. */
@Timeout(180)
class TypedExternalPolynomialComparisonWorkerTest {
    @Test void baselineDoesNotTrainAndReturnsReplayedSourceOnlyImprovement() {
        var worker = new TypedExternalPolynomialComparisonWorker();
        var setup = worker.initialize("BASE");
        assertEquals(0L, setup.get("trainingWork"));
        assertEquals(0, setup.get("learnedPrograms"));
        var result = worker.run("BASE", "(z+0)*1+101");
        assertEquals("CANDIDATE", result.get("status"));
        assertEquals("", result.get("target"));
        assertEquals(false, result.get("targetReached"));
        assertTrue((Long) result.get("outputCost") < (Long) result.get("inputCost"));
        assertTrue((Long) result.get("selectedReplayWork") > 0);
        assertTrue((Long) result.get("selectionWork") > 0);
        assertFalse(((List<?>) result.get("witness")).isEmpty());
        assertDoesNotThrow(() -> LearnedSchedulingArtifacts.json(result));
    }
    @Test void noOpAndRepeatedQueriesRetainFrozenEvidence() {
        var worker = new TypedExternalPolynomialComparisonWorker();
        var setup = worker.initialize("EXPERT");
        assertEquals(0L, setup.get("trainingWork"));
        var first = worker.run("EXPERT", "103");
        var second = worker.run("EXPERT", "103");
        assertEquals("103", first.get("output"));
        assertEquals(0L, first.get("selectedReplayWork"));
        assertEquals(List.of(), first.get("witness"));
        assertEquals(first.get("search"), second.get("search"));
        assertEquals(setup.get("modelHash"), second.get("modelHash"));
    }
    @Test void realLearnerAndPolicyTrainingAreRetainedAndFrozenBeforeQueries() {
        var worker = new TypedExternalPolynomialComparisonWorker();
        var setup = worker.initialize("LEARNED_RANKED");
        assertTrue((Long) setup.get("trainingWork") > 0);
        assertTrue((Integer) setup.get("learnedPrograms") > 0);
        assertTrue((Long) ((Map<?, ?>) setup.get("trainingWorkComponents")).get("policyTrials") > 0);
        var first = worker.run("LEARNED_RANKED", "((m+n)*(m-n)+n*n)+107");
        var second = worker.run("LEARNED_RANKED", "((m+n)*(m-n)+n*n)+107");
        assertEquals(false, first.get("targetReached"));
        assertEquals(first.get("search"), second.get("search"));
        assertEquals(setup.get("modelHash"), second.get("modelHash"));
    }
    @Test void hornerProfileUsesTheOrdinaryBudgetAndRetainsIndependentExactReplay() {
        String profile = "POLYNOMIAL_HORNER_V1";
        var worker = new TypedExternalPolynomialComparisonWorker();
        var setup = worker.initialize(profile);
        assertEquals(0L, setup.get("trainingWork"));
        assertTrue(setup.get("model").toString().contains(profile));
        for (String source : List.of("2*a*b*c+3*c*c-b*a*b*c+3*c*c-b",
                "1+x/2-x*x/8+x*x*x/16-5*x*x*x*x/128", "7*x/5-13*x*x*x/25")) {
            var result = worker.run(profile, source);
            assertTrue((Long) result.get("outputCost") < (Long) result.get("inputCost"), result::toString);
            assertEquals(true, result.get("internalWorkWithinBudget"), result::toString);
            assertTrue((Long) result.get("selectedReplayWork") > 0);
            assertTrue(result.get("witness").toString().contains("EXACT_CONSTRUCTION_REPLAYED"));
            assertEquals(setup.get("modelHash"), result.get("modelHash"));
            new de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis()
                .requireEquivalent(source, (String) result.get("output"));
        }
    }
    @Test void rejectsLifecycleProfileSwitchAndTrainReuse() {
        var worker = new TypedExternalPolynomialComparisonWorker();
        assertThrows(IllegalStateException.class, () -> worker.run("BASE", "x+2"));
        assertThrows(IllegalArgumentException.class, () -> worker.initialize("unknown"));
        worker.initialize("BASE");
        assertThrows(IllegalStateException.class, () -> worker.initialize("BASE"));
        assertThrows(IllegalArgumentException.class, () -> worker.run("EXPERT", "x+2"));
        assertThrows(IllegalArgumentException.class, () -> worker.run("BASE", "(a+b)*(a-b)+b*b+3"));
        assertThrows(IllegalArgumentException.class, () -> worker.run("BASE", "sin(x)"));
    }
    @Test void rejectsDuplicateTrailingAndTargetBearingRequests() throws Exception {
        var worker = new TypedExternalPolynomialComparisonWorker();
        assertThrows(Exception.class, () -> worker.handle("{\"op\":\"initialize\",\"op\":\"run\",\"profile\":\"BASE\"}"));
        assertThrows(Exception.class, () -> worker.handle("{\"op\":\"initialize\",\"profile\":\"BASE\"} {}"));
        assertThrows(IllegalArgumentException.class, () -> worker.handle("{\"op\":\"run\",\"profile\":\"BASE\",\"source\":\"x\",\"target\":\"x\"}"));
        assertEquals("INITIALIZED", worker.handle("{\"op\":\"initialize\",\"profile\":\"BASE\"}").get("status"));
    }
}
