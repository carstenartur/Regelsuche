package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProofCarryingShowcaseTrainAndFreezeCommandTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsAProductionEvaluatorCompatibleTrainOnlyConfiguration() {
        ProofCarryingShowcasePlan showcasePlan =
            ProofCarryingShowcasePlan.read(repositoryRoot().resolve(
                "research/showcase/proof-carrying-self-improvement/"
                    + "showcase-plan.json"));

        var configuration =
            ProofCarryingShowcaseTrainAndFreezeCommand.configuration(
                showcasePlan);
        Set<FitnessComponent> components = Set.copyOf(
            configuration.study().fitnessWeights().stream()
                .map(value -> value.component())
                .toList());

        assertTrue(
            configuration.splitManifest()
                .heldOutMaterializationDeferred());
        assertTrue(
            configuration.splitManifest().validationCases().isEmpty());
        assertTrue(
            configuration.splitManifest().finalTestCases().isEmpty());
        assertEquals(
            EvolutionRewriteProgramStudyPlan.FinalTestPolicy
                .ONE_TIME_AFTER_FROZEN_TRAIN_SELECTION_AND_PUBLIC_RANDOMNESS,
            configuration.study().finalTestPolicy());
        assertEquals(
            Set.of(
                FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
                FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION,
                FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION,
                FitnessComponent.SUPPORT,
                FitnessComponent.ASSUMPTION_SIMPLICITY,
                FitnessComponent.CANDIDATE_COMPLEXITY,
                FitnessComponent.PROOF_COST_PROXY),
            components);
        assertEquals(
            configuration.protocol(),
            RewriteProgramFitnessComposition.exactRationalTrainEvaluator(
                configuration.trainSuite(),
                components).protocol());
        configuration.study().requireInputs(
            configuration.splitManifest(),
            configuration.trainSuite(),
            configuration.protocol(),
            configuration.mutationCatalog(),
            configuration.seeds());
        assertFalse(
            configuration.study().toCanonicalJson().contains(
                "STRUCTURAL_DIVERSITY"));
        assertFalse(
            configuration.study().toCanonicalJson().contains(
                "COUNTEREXAMPLE_RISK"));
    }

    @Test
    void rejectsCallerControlledFreezeTimeBeforeReadingInputs() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcaseTrainAndFreezeCommand.main(
                new String[]{
                    "missing-plan.json",
                    "a".repeat(40),
                    "missing-output",
                    "2000000000"
                }));
    }

    @Test
    void publishesAllArtifactsAsOneNewAtomicDirectory() throws IOException {
        Path output = temporaryDirectory.resolve("published-freeze");
        LinkedHashMap<String, String> prerequisites = new LinkedHashMap<>();
        prerequisites.put("train.json", "{\"stage\":\"TRAIN\"}");
        prerequisites.put(
            "selection.json",
            "{\"stage\":\"TRAIN_SELECTION\"}");

        Path published =
            ProofCarryingShowcaseTrainAndFreezeCommand.publishAtomically(
                output,
                prerequisites,
                "candidate-freeze.json",
                "{\"status\":\"CANDIDATE_FROZEN_FINAL_TEST_UNSEEN\"}");

        assertEquals(output.toAbsolutePath().normalize(), published);
        assertEquals(
            "{\"stage\":\"TRAIN\"}",
            Files.readString(output.resolve("train.json")));
        assertEquals(
            "{\"status\":\"CANDIDATE_FROZEN_FINAL_TEST_UNSEEN\"}",
            Files.readString(output.resolve("candidate-freeze.json")));
        assertThrows(
            IllegalStateException.class,
            () -> ProofCarryingShowcaseTrainAndFreezeCommand
                .publishAtomically(
                    output,
                    prerequisites,
                    "candidate-freeze.json",
                    "{}"));
    }

    @Test
    void invalidArtifactNamesLeaveNoVisiblePartialOutput() {
        Path output = temporaryDirectory.resolve("invalid-freeze");
        LinkedHashMap<String, String> prerequisites = new LinkedHashMap<>();
        prerequisites.put("../escape.json", "{}");

        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcaseTrainAndFreezeCommand
                .publishAtomically(
                    output,
                    prerequisites,
                    "candidate-freeze.json",
                    "{}"));
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(temporaryDirectory.resolve("escape.json")));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    || Files.isRegularFile(
                        current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
