package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramPopulationSchemaContractTest {
    @Test
    void schemasCoverRuntimeVocabularyAndLaterStagesFailClosed()
            throws Exception {
        String study = readSchema(
            "regelsuche-evolution-rewrite-program-study-plan-v1.schema.json");
        String generation = readSchema(
            "regelsuche-evolution-rewrite-program-generation-report-v1.schema.json");
        String run = readSchema(
            "regelsuche-evolution-rewrite-program-population-run-v1.schema.json");
        String checkpoint = readSchema(
            "regelsuche-evolution-rewrite-program-population-checkpoint-v1.schema.json");

        assertTrue(study.contains(
            "regelsuche.evolution-rewrite-program-study-plan/v1"));
        assertTrue(study.contains("\"const\": \"NOT_STARTED\""));
        assertTrue(study.contains(
            "\"const\": \"ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION\""));
        assertTrue(study.contains("\"additionalProperties\": false"));
        for (EvolutionRewriteProgramMutationKind kind :
                EvolutionRewriteProgramMutationKind.values()) {
            assertTrue(study.contains("\"" + kind.name() + "\""),
                kind.name());
            assertTrue(generation.contains("\"" + kind.name() + "\""),
                kind.name());
        }
        for (EvolutionStudyPlan.FitnessComponent component :
                EvolutionStudyPlan.FitnessComponent.values()) {
            assertTrue(study.contains("\"" + component.name() + "\""),
                component.name());
        }

        assertTrue(generation.contains(
            "regelsuche.evolution-rewrite-program-generation-report/v1"));
        assertTrue(generation.contains("\"selectedCandidateHashes\""));
        assertTrue(generation.contains("\"rejections\""));
        assertTrue(generation.contains("\"additionalProperties\": false"));
        for (EvolutionRewriteProgramPopulationEngine.GenerationOutcome outcome :
                EvolutionRewriteProgramPopulationEngine.GenerationOutcome.values()) {
            assertTrue(generation.contains("\"" + outcome.name() + "\""),
                outcome.name());
        }

        assertTrue(run.contains(
            "regelsuche.evolution-rewrite-program-population-run/v1"));
        assertTrue(run.contains("\"finalCandidateHashes\""));
        assertTrue(run.contains("\"const\": \"NOT_EVALUATED\""));
        assertTrue(run.contains("\"additionalProperties\": false"));
        for (EvolutionRewriteProgramPopulationEngine.TerminalOutcome outcome :
                EvolutionRewriteProgramPopulationEngine.TerminalOutcome.values()) {
            assertTrue(run.contains("\"" + outcome.name() + "\""),
                outcome.name());
        }

        assertTrue(checkpoint.contains(
            "regelsuche.evolution-rewrite-program-population-checkpoint/v1"));
        assertTrue(checkpoint.contains("\"populationCandidateHashes\""));
        assertTrue(checkpoint.contains("\"evaluationHashes\""));
        assertTrue(checkpoint.contains("\"generationReportHashes\""));
        assertTrue(checkpoint.contains("\"const\": \"NOT_EVALUATED\""));
        assertTrue(checkpoint.contains("\"additionalProperties\": false"));
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
