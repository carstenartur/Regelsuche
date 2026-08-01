package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramFreezeSchemaContractTest {
    @Test
    void schemasCoverRuntimeVocabularyAndRemainFailClosed() throws Exception {
        String heldOut = readSchema(
            "regelsuche-evolution-rewrite-program-held-out-commitment-v1.schema.json");
        String thresholds = readSchema(
            "regelsuche-evolution-rewrite-program-acceptance-thresholds-v1.schema.json");
        String performance = readSchema(
            "regelsuche-evolution-rewrite-program-performance-plan-v1.schema.json");
        String receipt = readSchema(
            "regelsuche-evolution-rewrite-program-freeze-receipt-v1.schema.json");

        assertTrue(heldOut.contains(
            "regelsuche.evolution-rewrite-program-held-out-commitment/v1"));
        assertTrue(heldOut.contains("\"additionalProperties\": false"));
        for (EvolutionRewriteProgramHeldOutCommitment.Split value :
                EvolutionRewriteProgramHeldOutCommitment.Split.values()) {
            assertTrue(heldOut.contains("\"" + value.name() + "\""),
                value.name());
        }
        for (EvolutionRewriteProgramHeldOutCommitment.RevealPolicy value :
                EvolutionRewriteProgramHeldOutCommitment.RevealPolicy.values()) {
            assertTrue(heldOut.contains("\"" + value.name() + "\""),
                value.name());
        }

        assertTrue(thresholds.contains(
            "regelsuche.evolution-rewrite-program-acceptance-thresholds/v1"));
        assertTrue(thresholds.contains("\"maximumCorrectnessRegressions\""));
        assertTrue(thresholds.contains("\"minimum\": 50"));
        assertTrue(thresholds.contains(
            "\"const\": \"NEWLY_REACHED_OR_MATERIAL_WORK_REDUCTION\""));
        assertTrue(thresholds.contains(
            "\"const\": \"NEWLY_REACHED_REQUIRED\""));
        assertTrue(thresholds.contains("\"const\": 0"));
        assertTrue(thresholds.contains("\"additionalProperties\": false"));
        for (EvolutionRewriteProgramAcceptanceThresholds.SuccessRoute value :
                EvolutionRewriteProgramAcceptanceThresholds.SuccessRoute.values()) {
            assertTrue(thresholds.contains("\"" + value.name() + "\""),
                value.name());
        }
        for (EvolutionRewriteProgramAcceptanceThresholds.NullResultPolicy value :
                EvolutionRewriteProgramAcceptanceThresholds.NullResultPolicy.values()) {
            assertTrue(thresholds.contains("\"" + value.name() + "\""),
                value.name());
        }

        assertTrue(performance.contains(
            "regelsuche.evolution-rewrite-program-performance-plan/v1"));
        assertTrue(performance.contains(
            "CANONICAL_PRIMITIVE_AND_TOTAL_WORK_LEDGER"));
        assertTrue(performance.contains(
            "ENVIRONMENT_QUALIFIED_ENGINEERING_DIAGNOSTIC"));
        assertTrue(performance.contains("\"additionalProperties\": false"));
        for (EvolutionRewriteProgramPerformancePlan.MeasurementLayer value :
                EvolutionRewriteProgramPerformancePlan.MeasurementLayer.values()) {
            assertTrue(performance.contains("\"" + value.name() + "\""),
                value.name());
        }

        assertTrue(receipt.contains(
            "regelsuche.evolution-rewrite-program-freeze-receipt/v1"));
        assertTrue(receipt.contains("\"const\": \"FROZEN_NOT_RUN\""));
        assertTrue(receipt.contains("\"const\": \"NOT_EVALUATED\""));
        assertTrue(receipt.contains("\"additionalProperties\": false"));
        for (EvolutionRewriteProgramFreezeReceipt.DirtyStatePolicy value :
                EvolutionRewriteProgramFreezeReceipt.DirtyStatePolicy.values()) {
            assertTrue(receipt.contains("\"" + value.name() + "\""),
                value.name());
        }
        for (EvolutionRewriteProgramFreezeReceipt.FreezeStatus value :
                EvolutionRewriteProgramFreezeReceipt.FreezeStatus.values()) {
            assertTrue(receipt.contains("\"" + value.name() + "\""),
                value.name());
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
