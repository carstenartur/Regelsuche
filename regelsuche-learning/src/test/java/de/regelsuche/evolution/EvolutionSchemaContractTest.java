package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        String generation = readSchema(
            "regelsuche-evolution-generation-report-v1.schema.json");
        String population = readSchema(
            "regelsuche-evolution-population-run-v1.schema.json");
        String checkpoint = readSchema(
            "regelsuche-evolution-population-checkpoint-v1.schema.json");
        String trainSuite = readSchema(
            "regelsuche-evolution-train-search-suite-v1.schema.json");
        String trainFitness = readSchema(
            "regelsuche-evolution-train-fitness-v1.schema.json");
        String rewriteProgram = readSchema(
            "regelsuche-evolution-rewrite-program-plan-v1.schema.json");
        String rewriteProgramMutations = readSchema(
            "regelsuche-evolution-rewrite-program-mutation-batch-v1.schema.json");
        String rewriteProgramDiagnostics = readSchema(
            "regelsuche-evolution-rewrite-program-train-diagnostics-v1.schema.json");

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
            assertTrue(generation.contains("\"" + kind.name() + "\""),
                kind.name());
        }
        for (EvolutionStudyPlan.FitnessComponent component :
                EvolutionStudyPlan.FitnessComponent.values()) {
            assertTrue(study.contains("\"" + component.name() + "\""),
                component.name());
            assertTrue(generation.contains("\"" + component.name() + "\""),
                component.name());
            assertTrue(trainFitness.contains("\"" + component.name() + "\""),
                component.name());
        }
        for (EvolutionPopulationEngine.GenerationOutcome outcome :
                EvolutionPopulationEngine.GenerationOutcome.values()) {
            assertTrue(generation.contains("\"" + outcome.name() + "\""),
                outcome.name());
        }
        for (EvolutionPopulationEngine.TerminalOutcome outcome :
                EvolutionPopulationEngine.TerminalOutcome.values()) {
            assertTrue(population.contains("\"" + outcome.name() + "\""),
                outcome.name());
        }

        assertTrue(split.contains("regelsuche.evolution-split-manifest/v1"));
        assertTrue(split.contains("\"finalTestCases\""));
        assertTrue(split.contains("\"additionalProperties\": false"));
        assertTrue(study.contains("regelsuche.evolution-study-plan/v1"));
        assertTrue(study.contains("\"const\": \"NOT_STARTED\""));
        assertTrue(study.contains(
            "\"const\": \"ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION\""));
        assertTrue(study.contains("\"additionalProperties\": false"));
        assertTrue(generation.contains(
            "regelsuche.evolution-generation-report/v1"));
        assertTrue(generation.contains("\"rawComponents\""));
        assertTrue(generation.contains("\"additionalProperties\": false"));
        assertTrue(population.contains("regelsuche.evolution-population-run/v1"));
        assertTrue(population.contains("\"validationStatus\""));
        assertTrue(population.contains("\"const\": \"NOT_EVALUATED\""));
        assertTrue(population.contains("\"additionalProperties\": false"));
        assertTrue(checkpoint.contains(
            "regelsuche.evolution-population-checkpoint/v1"));
        assertTrue(checkpoint.contains("\"mutationCatalogHash\""));
        assertTrue(checkpoint.contains("\"evaluations\""));
        assertTrue(checkpoint.contains("\"generationReports\""));
        assertTrue(checkpoint.contains("\"validationStatus\""));
        assertTrue(checkpoint.contains("\"finalTestStatus\""));
        assertTrue(checkpoint.contains("\"const\": \"NOT_EVALUATED\""));
        assertTrue(checkpoint.contains("\"additionalProperties\": false"));
        assertTrue(trainSuite.contains(
            "regelsuche.evolution-train-search-suite/v1"));
        assertTrue(trainSuite.contains("\"targetExpression\""));
        assertTrue(trainSuite.contains("\"additionalProperties\": false"));
        assertTrue(trainFitness.contains(
            "regelsuche.evolution-train-fitness/v1"));
        assertTrue(trainFitness.contains("\"baselineExploredStates\""));
        assertTrue(trainFitness.contains("\"validationStatus\""));
        assertTrue(trainFitness.contains("\"const\": \"NOT_EVALUATED\""));
        assertTrue(trainFitness.contains("\"additionalProperties\": false"));

        assertTrue(rewriteProgram.contains(
            "regelsuche.evolution-rewrite-program-plan/v1"));
        assertTrue(rewriteProgram.contains("\"additionalProperties\": false"));
        assertTrue(rewriteProgram.contains("\"const\": \"SOURCE\""));
        assertTrue(rewriteProgram.contains("\"const\": \"SEQUENCE\""));
        assertTrue(rewriteProgram.contains(
            "\"const\": \"FIRST_APPLICABLE\""));
        assertTrue(rewriteProgram.contains("\"const\": \"REPEAT\""));
        assertTrue(rewriteProgram.contains("\"const\": \"PRUNE\""));
        for (EvolutionRewriteProgramPlan.RequirementKind kind :
                EvolutionRewriteProgramPlan.RequirementKind.values()) {
            assertTrue(rewriteProgram.contains("\"" + kind.name() + "\""),
                kind.name());
        }
        for (EvolutionRewriteProgramPlan.PriorityKind kind :
                EvolutionRewriteProgramPlan.PriorityKind.values()) {
            assertTrue(rewriteProgram.contains("\"" + kind.name() + "\""),
                kind.name());
        }

        assertTrue(rewriteProgramMutations.contains(
            "regelsuche.evolution-rewrite-program-mutation-batch/v1"));
        assertTrue(rewriteProgramMutations.contains(
            "\"additionalProperties\": false"));
        assertTrue(rewriteProgramMutations.contains("\"acceptedPlanHashes\""));
        assertTrue(rewriteProgramMutations.contains("\"blockers\""));
        for (EvolutionRewriteProgramMutationKind kind :
                EvolutionRewriteProgramMutationKind.values()) {
            assertTrue(rewriteProgramMutations.contains(
                "\"" + kind.name() + "\""), kind.name());
            assertTrue(rewriteProgramDiagnostics.contains(
                "\"" + kind.name() + "\""), kind.name());
        }
        for (DeterministicRewriteProgramMutator.MutationStatus status :
                DeterministicRewriteProgramMutator.MutationStatus.values()) {
            assertTrue(rewriteProgramMutations.contains(
                "\"" + status.name() + "\""), status.name());
        }

        assertTrue(rewriteProgramDiagnostics.contains(
            "regelsuche.evolution-rewrite-program-train-diagnostics/v1"));
        assertTrue(rewriteProgramDiagnostics.contains(
            "\"additionalProperties\": false"));
        assertTrue(rewriteProgramDiagnostics.contains(
            "\"const\": \"TRAIN_ONLY\""));
        assertTrue(rewriteProgramDiagnostics.contains(
            "\"eligibleProposalCountsByMutationKind\""));
        assertTrue(rewriteProgramDiagnostics.contains(
            "\"maxAcceptedOnlyRejectionCountsByMutationKind\""));
        assertTrue(rewriteProgramDiagnostics.contains(
            "\"minimumStructuralPrimitivePathSteps\""));
        assertTrue(rewriteProgramDiagnostics.contains(
            "\"mutationBatchJsonHash\""));
        assertFalse(rewriteProgramDiagnostics.contains("\"validationCases\""));
        assertFalse(rewriteProgramDiagnostics.contains("\"finalTestOutcome\""));
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
