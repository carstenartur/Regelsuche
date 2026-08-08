package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionGenome.SourceSplit;
import de.regelsuche.evolution.EvolutionSplitManifest.CaseReference;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionSplitManifestTrainOnlyTest {
    @Test
    void freezesTrainWhileLeavingBothHeldOutStagesUnmaterialized() {
        List<CaseReference> train = List.of(reference("train_case", "train_family"));

        EvolutionSplitManifest manifest = EvolutionSplitManifest.createTrainOnly(
            "showcase_study",
            EvolutionGenome.hash("showcase corpus"),
            EvolutionGenome.hash("showcase features"),
            train);

        assertTrue(manifest.heldOutMaterializationDeferred());
        assertTrue(manifest.validationCases().isEmpty());
        assertTrue(manifest.finalTestCases().isEmpty());
        assertEquals(SourceSplit.TRAIN, manifest.trainingScope().sourceSplit());
        assertTrue(manifest.toCanonicalJson().contains(
            "\"heldOutMaterialization\":\"DEFERRED_TO_PUBLIC_RANDOMNESS\""));
        assertEquals(
            manifest,
            EvolutionSplitManifest.createTrainOnly(
                "showcase_study",
                EvolutionGenome.hash("showcase corpus"),
                EvolutionGenome.hash("showcase features"),
                train));
    }

    @Test
    void legacyFactoryStillRequiresConcreteValidationAndFinalTest() {
        List<CaseReference> train = List.of(reference("train_case", "train_family"));

        assertThrows(
            IllegalArgumentException.class,
            () -> EvolutionSplitManifest.create(
                "showcase_study",
                EvolutionGenome.hash("showcase corpus"),
                EvolutionGenome.hash("showcase features"),
                train,
                List.of(),
                List.of()));
    }

    private static CaseReference reference(String caseId, String familyId) {
        return new CaseReference(
            caseId,
            familyId,
            EvolutionGenome.hash(caseId + " exact"),
            EvolutionGenome.hash(caseId + " alpha"),
            EvolutionGenome.hash(caseId + " input"),
            EvolutionGenome.hash(caseId + " target"));
    }
}
