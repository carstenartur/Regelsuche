package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.TerminalOutcome;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProofCarryingShowcaseTrainSelectionEvidenceTest {
    @Test
    void retainsCompleteNullSelectionInsteadOfThrowing() {
        var seed = alternative(
            "seed", 700, 1,
            List.of("SEED_EXACT_EQUIVALENT", "SEED_ALPHA_EQUIVALENT",
                "MISSING_COMPOSITION_TOPOLOGY",
                "MISSING_DECISION_TOPOLOGY",
                "PRIMITIVE_PATH_DEPTH_BELOW_3"),
            false);
        var compositeButShallow = alternative(
            "shallow", 650, 5,
            List.of("PRIMITIVE_PATH_DEPTH_BELOW_3"),
            false);

        var evidence = ProofCarryingShowcaseTrainSelectionEvidence
            .createFromAlternatives(
                "showcase",
                hash("plan"),
                hash("retained"),
                hash("population"),
                hash("generation"),
                TerminalOutcome.STAGNATED,
                List.of(compositeButShallow, seed));

        assertEquals(
            ProofCarryingShowcaseTrainSelectionEvidence.NONE,
            evidence.status());
        assertFalse(evidence.eligibleCandidateAvailable());
        assertNull(evidence.selectedCandidateHash());
        assertNull(evidence.selectedCandidateAlphaStructuralHash());
        assertNull(evidence.selectedPlanHash());
        assertEquals(2, evidence.alternatives().size());
        assertEquals(
            List.of(seed.candidateHash(), compositeButShallow.candidateHash())
                .stream().sorted().toList(),
            evidence.alternatives().stream()
                .map(value -> value.candidateHash()).toList());
        assertEquals(
            evidence,
            ProofCarryingShowcaseTrainSelectionEvidence.fromCanonicalJson(
                evidence.toCanonicalJson()));
    }

    @Test
    void eligibleSelectionUsesTheFrozenRanking() {
        var lowerFitness = alternative("low", 600, 3, List.of(), true);
        var larger = alternative("large", 800, 9, List.of(), true);
        var winner = alternative("winner", 800, 5, List.of(), true);

        var evidence = ProofCarryingShowcaseTrainSelectionEvidence
            .createFromAlternatives(
                "showcase",
                hash("plan"),
                hash("retained"),
                hash("population"),
                hash("generation"),
                TerminalOutcome.COMPLETED,
                List.of(lowerFitness, larger, winner));

        assertEquals(
            ProofCarryingShowcaseTrainSelectionEvidence.ELIGIBLE,
            evidence.status());
        assertTrue(evidence.eligibleCandidateAvailable());
        assertEquals(winner.candidateHash(), evidence.selectedCandidateHash());
        assertEquals(
            winner.alphaStructuralHash(),
            evidence.selectedCandidateAlphaStructuralHash());
        assertEquals(winner.planHash(), evidence.selectedPlanHash());
    }

    @Test
    void missingTerminalEvaluationUsesTheProductionFreezerDiagnostic() {
        var failure = assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcaseCandidateFreezer.alternative(
                null,
                null,
                Set.of(),
                Set.of(),
                3));

        assertEquals(
            "terminal candidate lacks retained TRAIN evaluation",
            failure.getMessage());
    }

    private static ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative
            alternative(
        String id,
        int fitness,
        int nodeCount,
        List<String> freezeBlockers,
        boolean eligible
    ) {
        return new ProofCarryingShowcaseCandidateFreezer.CandidateSelection.Alternative(
            hash("candidate-" + id),
            hash("alpha-" + id),
            hash("genome-" + id),
            hash("plan-" + id),
            hash("evaluation-" + id),
            fitness,
            Map.of(),
            List.of(),
            false,
            false,
            nodeCount,
            true,
            true,
            3,
            freezeBlockers,
            eligible);
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }
}
