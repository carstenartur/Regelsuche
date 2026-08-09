package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProofCarryingShowcaseTrainPreflightCommandTest {
    @Test
    void rejectsExtraClockOrAuthorityArgumentsBeforeReadingInputs() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcaseTrainPreflightCommand.main(
                new String[]{
                    "missing-plan.json",
                    "missing-output",
                    "caller-controlled-extra"
                }));
    }

    @Test
    void preflightImplementationHasNoFreezeRandomnessOrFinalTestStage()
            throws Exception {
        String source = Files.readString(repositoryRoot().resolve(
            "app/src/main/java/de/regelsuche/evolution/"
                + "ProofCarryingShowcaseTrainPreflightCommand.java"));

        assertFalse(source.contains("ProofCarryingShowcaseCandidateFreezer"));
        assertFalse(source.contains("ProofCarryingShowcaseCandidateFreeze"));
        assertFalse(source.contains("ProofCarryingShowcasePublicRandomness"));
        assertFalse(source.contains("ProofCarryingShowcaseSeedReceipt"));
        assertFalse(source.contains("ProofCarryingShowcaseCaseGenerator"));
        assertFalse(source.contains("ProofCarryingShowcaseGeneratedFinalTest"));
        assertFalse(source.contains("Instant.now"));
        assertFalse(source.contains("candidate-freeze.json"));
    }

    @Test
    void writtenPreflightRejectsFreezeLikeOrUnknownStatuses() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ProofCarryingShowcaseTrainPreflightCommand.WrittenPreflight(
                Path.of("output"),
                "CANDIDATE_FROZEN_FINAL_TEST_UNSEEN",
                EvolutionGenome.hash("selection")));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}