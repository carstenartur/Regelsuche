package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.CaseCommitment;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.RevealPolicy;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramFreezeNegativeControlsTest {
    @Test
    void splitCannotUseTheOtherStageRevealPolicy() {
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionRewriteProgramHeldOutCommitment(
                EvolutionRewriteProgramHeldOutCommitment.SCHEMA,
                "negative_freeze_study",
                Split.FINAL_TEST,
                List.of(caseCommitment("final_negative_case", "a")),
                hash("sealed-final-reveal"),
                RevealPolicy.AFTER_TRAIN_POPULATION_COMPLETE,
                hash("unreachable-content-hash")));
    }

    @Test
    void finalSurfaceWithFewerThanThreeFamiliesCannotBeFrozen() {
        EvolutionRewriteProgramHeldOutCommitment finalTest =
            EvolutionRewriteProgramHeldOutCommitment.create(
                "negative_freeze_study",
                Split.FINAL_TEST,
                List.of(
                    caseCommitment("final_negative_case_a", "a"),
                    caseCommitment("final_negative_case_b", "b")),
                hash("sealed-two-family-final-reveal"));
        EvolutionRewriteProgramAcceptanceThresholds thresholds =
            EvolutionRewriteProgramAcceptanceThresholds.create(
                2, 2, 1, true, 100, 3, 0, 12, 8, 2);

        assertThrows(IllegalArgumentException.class,
            () -> thresholds.requireCompatibleWithFinalSurface(finalTest));
    }

    private static CaseCommitment caseCommitment(
        String caseId,
        String material
    ) {
        return new CaseCommitment(
            caseId,
            hash("family-" + material),
            hash("input-" + material),
            hash("target-" + material),
            hash("assumptions-" + material),
            hash("exact-" + material),
            hash("alpha-" + material),
            hash("difficulty-" + material),
            hash("terminal-" + material),
            hash("reveal-entry-" + material));
    }

    private static String hash(String material) {
        return EvolutionGenome.hash(material);
    }
}
