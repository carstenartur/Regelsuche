package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramFitnessSchemaContractTest {
    @Test
    void schemasCoverRuntimeProfilesStatusesAndFailClosed() throws Exception {
        String candidate = readSchema(
            "regelsuche-evolution-rewrite-program-candidate-v1.schema.json");
        String suite = readSchema(
            "regelsuche-evolution-rewrite-program-train-suite-v1.schema.json");
        String fitness = readSchema(
            "regelsuche-evolution-rewrite-program-train-fitness-v1.schema.json");

        assertTrue(candidate.contains(
            "regelsuche.evolution-rewrite-program-candidate/v1"));
        assertTrue(candidate.contains("\"additionalProperties\": false"));
        assertTrue(candidate.contains("\"genomeAlphaStructuralHash\""));
        assertTrue(candidate.contains("\"planAlphaStructuralHash\""));

        assertTrue(suite.contains(
            "regelsuche.evolution-rewrite-program-train-suite/v1"));
        assertTrue(suite.contains("\"const\": \"TRAIN\""));
        assertTrue(suite.contains("\"assumptions\""));
        assertTrue(suite.contains("\"additionalProperties\": false"));
        for (EvolutionRewriteProgramTrainSuite.EvaluatorProfile profile :
                EvolutionRewriteProgramTrainSuite.EvaluatorProfile.values()) {
            assertTrue(suite.contains("\"" + profile.name() + "\""),
                profile.name());
        }

        assertTrue(fitness.contains(
            "regelsuche.evolution-rewrite-program-train-fitness/v1"));
        assertTrue(fitness.contains("\"candidateHash\""));
        assertTrue(fitness.contains("\"programUsed\""));
        assertTrue(fitness.contains("\"reachabilityRegression\""));
        assertTrue(fitness.contains("\"correctnessFailure\""));
        assertTrue(fitness.contains("\"const\": \"NOT_EVALUATED\""));
        assertTrue(fitness.contains("\"additionalProperties\": false"));
        for (EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness status :
                EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness.values()) {
            assertTrue(fitness.contains("\"" + status.name() + "\""),
                status.name());
        }
        for (EvolutionStudyPlan.FitnessComponent component :
                EvolutionStudyPlan.FitnessComponent.values()) {
            assertTrue(fitness.contains("\"" + component.name() + "\""),
                component.name());
        }
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
