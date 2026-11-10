package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeCostGateTest {
    private static KnowledgeCostGate.Sample sample(long wall, long cpu, boolean solved, long score) {
        return new KnowledgeCostGate.Sample(new KnowledgeCostGate.Cost(wall, cpu), solved, score, true);
    }
    private static KnowledgeCostGate.Trial faster() {
        return new KnowledgeCostGate.Trial(sample(10, 8, true, 3), sample(2, 1, true, 3));
    }
    @Test void actualPaidCostsMustBeRepaidInBothResources() {
        var paid = new KnowledgeCostGate.Cost(100, 100);
        assertEquals(KnowledgeCostGate.Decision.ADOPT, KnowledgeCostGate.evaluate(paid, List.of(faster()), 100));
        assertEquals(KnowledgeCostGate.Decision.NO_GAIN, KnowledgeCostGate.evaluate(paid, List.of(faster()), 1));
        assertEquals(KnowledgeCostGate.Decision.NO_GAIN, KnowledgeCostGate.evaluate(new KnowledgeCostGate.Cost(1, 1000), List.of(faster()), 100));
        assertEquals(KnowledgeCostGate.Decision.NO_GAIN, KnowledgeCostGate.evaluate(new KnowledgeCostGate.Cost(1000, 1), List.of(faster()), 100));
    }
    @Test void oneRegressedTaskCannotBeCancelledByOtherWins() {
        for (var candidate : List.of(sample(1, 1, false, 3), sample(1, 1, true, 4))) {
            var bad = new KnowledgeCostGate.Trial(sample(100, 100, true, 3), candidate);
            assertEquals(KnowledgeCostGate.Decision.QUALITY_REGRESSION,
                KnowledgeCostGate.evaluate(new KnowledgeCostGate.Cost(1, 1), List.of(faster(), bad), 1000));
        }
    }
    @Test void equalOrSlowerCandidatesDoNotBenefitFromLargeHorizons() {
        for (var candidate : List.of(sample(10, 8, true, 3), sample(11, 9, true, 3), sample(1, 9, true, 3))) {
            var trial = new KnowledgeCostGate.Trial(sample(10, 8, true, 3), candidate);
            assertEquals(KnowledgeCostGate.Decision.NO_GAIN,
                KnowledgeCostGate.evaluate(new KnowledgeCostGate.Cost(1, 1), List.of(trial), Long.MAX_VALUE));
        }
    }
    @Test void absentCpuAndIncompleteExecutionCannotAuthorizeAdoption() {
        assertEquals(KnowledgeCostGate.Decision.INCOMPLETE,
            KnowledgeCostGate.evaluate(new KnowledgeCostGate.Cost(100, -1), List.of(faster()), 1000));
        var unavailable = new KnowledgeCostGate.Trial(sample(10, -1, true, 3), sample(1, 1, true, 3));
        assertEquals(KnowledgeCostGate.Decision.INCOMPLETE,
            KnowledgeCostGate.evaluate(new KnowledgeCostGate.Cost(1, 1), List.of(unavailable), 1000));
        var partial = new KnowledgeCostGate.Sample(new KnowledgeCostGate.Cost(1, 1), true, 3, false);
        assertEquals(KnowledgeCostGate.Decision.INCOMPLETE,
            KnowledgeCostGate.evaluate(new KnowledgeCostGate.Cost(1, 1), List.of(new KnowledgeCostGate.Trial(sample(10, 8, true, 3), partial)), 1000));
    }
    @Test void exactArithmeticHandlesHorizonsAndSamplesBeyondLongProducts() {
        var trial = new KnowledgeCostGate.Trial(sample(Long.MAX_VALUE, Long.MAX_VALUE, true, 3), sample(1, 1, true, 3));
        assertEquals(KnowledgeCostGate.Decision.ADOPT,
            KnowledgeCostGate.evaluate(new KnowledgeCostGate.Cost(Long.MAX_VALUE, Long.MAX_VALUE), List.of(trial, trial), Long.MAX_VALUE));
        var reverse = new KnowledgeCostGate.Trial(trial.candidate(), trial.previous());
        assertEquals(KnowledgeCostGate.Decision.NO_GAIN,
            KnowledgeCostGate.evaluate(new KnowledgeCostGate.Cost(1, 1), List.of(reverse, reverse), Long.MAX_VALUE));
    }
    @Test void aSpecialisedWinCannotHideTheCostOfTheSevenOtherStreamRoles() {
        var specialised = new KnowledgeCostGate.Trial(sample(100, 100, true, 3), sample(10, 10, true, 3));
        var regression = new KnowledgeCostGate.Trial(sample(10, 10, true, 3), sample(30, 30, true, 3));
        var paid = new KnowledgeCostGate.Cost(1, 1);
        assertEquals(KnowledgeCostGate.Decision.ADOPT, KnowledgeCostGate.evaluate(paid, List.of(specialised), 128));
        assertEquals(KnowledgeCostGate.Decision.NO_GAIN, KnowledgeCostGate.evaluate(paid,
            List.of(regression, regression, specialised, regression, regression, regression, regression, regression), 128));
    }

    @Test void zeroHorizonEmptyTrialsAndNegativeCostsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeCostGate.Cost(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeCostGate.Cost(0, -2));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeCostGate.evaluate(new KnowledgeCostGate.Cost(0, 0), List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeCostGate.evaluate(new KnowledgeCostGate.Cost(0, 0), List.of(faster()), 0));
    }
}
