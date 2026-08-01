package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramFitnessSchemaContractTest {
    @Test
    void strictSchemasCoverCandidateSuitePrimitiveWorkAndEvidence()
            throws Exception {
        String candidate = readSchema(
            "regelsuche-evolution-rewrite-program-candidate-v1.schema.json");
        String suite = readSchema(
            "regelsuche-evolution-rewrite-program-train-suite-v1.schema.json");
        String fitness = readSchema(
            "regelsuche-evolution-rewrite-program-train-fitness-v1.schema.json");

        assertTrue(candidate.contains(
            "regelsuche.evolution-rewrite-program-candidate/v1"));
        assertTrue(candidate.contains("\"planAlphaStructuralHash\""));
        assertTrue(candidate.contains("\"additionalProperties\": false"));

        assertTrue(suite.contains(
            "regelsuche.evolution-rewrite-program-train-suite/v1"));
        assertTrue(suite.contains("\"const\": \"TRAIN\""));
        assertTrue(suite.contains("\"assumptions\""));
        assertTrue(suite.contains("\"primitiveWorkBudget\""));
        assertTrue(suite.contains("\"maxPrimitiveSteps\""));
        assertTrue(suite.contains("\"maxWorkUnits\""));
        assertTrue(suite.contains("\"additionalProperties\": false"));

        assertTrue(fitness.contains(
            "regelsuche.evolution-rewrite-program-train-fitness/v1"));
        assertTrue(fitness.contains("\"evaluationProtocolHash\""));
        assertTrue(fitness.contains("\"programUsed\""));
        assertTrue(fitness.contains("\"baselinePrimitiveSteps\""));
        assertTrue(fitness.contains("\"candidatePrimitiveSteps\""));
        assertTrue(fitness.contains("\"baselineOuterSearchWorkUnits\""));
        assertTrue(fitness.contains("\"candidateOuterSearchWorkUnits\""));
        assertTrue(fitness.contains("\"baselineOuterSearchWork\""));
        assertTrue(fitness.contains("\"candidateOuterSearchWork\""));
        assertTrue(fitness.contains("\"repeatedApplicationPrunes\""));
        assertTrue(fitness.contains("\"baselinePathAuditCalls\""));
        assertTrue(fitness.contains("\"candidatePathAuditCalls\""));
        assertTrue(fitness.contains("\"baselineTransformationWork\""));
        assertTrue(fitness.contains("\"candidateTransformationWork\""));
        assertTrue(fitness.contains("\"baselineTotalWorkUnits\""));
        assertTrue(fitness.contains("\"candidateTotalWorkUnits\""));
        assertTrue(fitness.contains("\"validationStatus\""));
        assertTrue(fitness.contains("\"const\": \"NOT_EVALUATED\""));
        assertTrue(fitness.contains("\"additionalProperties\": false"));
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
