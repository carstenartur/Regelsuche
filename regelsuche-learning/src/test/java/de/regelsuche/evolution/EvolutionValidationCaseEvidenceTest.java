package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EvolutionValidationCaseEvidenceTest {
    @Test
    void correctnessCannotBeClaimedWithoutReachability() {
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionValidationCaseEvidence(
                "case", "family", false, false,
                EvolutionCorrectnessStatus.CONFIRMED,
                EvolutionCorrectnessStatus.NOT_EVALUATED,
                "FRONTIER_EXHAUSTED", "FRONTIER_EXHAUSTED",
                -1, -1, 1, 1, 1, 1,
                false, false, false, false));
    }

    @Test
    void refutedNewlyReachedResultIsNotCountedAsNewlySolved() {
        EvolutionValidationCaseEvidence evidence =
            new EvolutionValidationCaseEvidence(
                "case", "family", false, true,
                EvolutionCorrectnessStatus.NOT_EVALUATED,
                EvolutionCorrectnessStatus.REFUTED,
                "FRONTIER_EXHAUSTED", "TARGET_REACHED_BUT_REFUTED",
                -1, 2, 1, 2, 1, 2,
                false, false, true, false);

        assertFalse(evidence.newlySolved());
    }
}
