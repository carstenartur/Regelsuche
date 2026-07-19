package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EvolutionSchemaContractTest {
    @Test
    void retainedSchemasCoverRuntimeEnumsAndFailClosed() throws Exception {
        String genome = readSchema("regelsuche-evolution-genome-v1.schema.json");
        String preflight = readSchema("regelsuche-evolution-preflight-v1.schema.json");
        String mutations = readSchema(
            "regelsuche-evolution-mutation-batch-v1.schema.json");
        String split = readSchema(
            "regelsuche-evolution-split-manifest-v1.schema.json");
        String study = readSchema(
            "regelsuche-evolution-study-plan-v1.schema.json");

        assertTrue(genome.contains("regelsuche.evolution-genome/v1"));
        assertTrue(genome.contains("\"const\": \"TRAIN\""));
        assertTrue(genome.contains("\"additionalProperties\": false"));
        for (EvolutionGenome.FitnessSignal signal :
                EvolutionGenome.FitnessSignal.values()) {
            assertTrue(genome.contains("\"" + signal.name() + "\""),
                signal.name());
        }
        for (EvolutionGenomeValidator.BlockerCode blocker :
                EvolutionGenomeValidator.BlockerCode.values()) {
            assertTrue(preflight.contains("\"" + blocker.name() + "\""),
                blocker.name());
        }
        for (EvolutionMutationKind kind : EvolutionMutationKind.values()) {
            assertTrue(mutations.contains("\"" + kind.name() + "\""),
                kind.name());
            assertTrue(study.contains("\"" + kind.name() + "\""),
                kind.name());
        }
        for (EvolutionStudyPlan.FitnessComponent component :
                EvolutionStudyPlan.FitnessComponent.values()) {
            assertTrue(study.contains("\"" + component.name() + "\""),
                component.name());
        }

        assertTrue(split.contains("regelsuche.evolution-split-manifest/v1"));
        assertTrue(split.contains("\"finalTestCases\""));
        assertTrue(split.contains("\"additionalProperties\": false"));
        assertTrue(study.contains("regelsuche.evolution-study-plan/v1"));
        assertTrue(study.contains("\"const\": \"NOT_STARTED\""));
        assertTrue(study.contains(
            "\"const\": \"ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION\""));
        assertTrue(study.contains("\"additionalProperties\": false"));
    }

    private static String readSchema(String fileName) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("repository root not found");
        }
        return Files.readString(root.resolve("docs").resolve("schemas")
            .resolve(fileName));
    }
}
