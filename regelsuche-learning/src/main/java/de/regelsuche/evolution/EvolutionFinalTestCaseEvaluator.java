package de.regelsuche.evolution;

import java.util.Objects;

@FunctionalInterface
public interface EvolutionFinalTestCaseEvaluator {
    Pair evaluate(
        EvolutionFinalTestSuite.CaseDefinition definition,
        EvaluationContext context
    ) throws Exception;

    record EvaluationContext(
        String baselineProfileHash,
        String selectedGenomeHash,
        EvolutionValidationSearchConfiguration selectedConfiguration
    ) {
        public EvaluationContext {
            EvolutionGenome.requireSha256(
                baselineProfileHash, "baselineProfileHash");
            EvolutionGenome.requireSha256(
                selectedGenomeHash, "selectedGenomeHash");
            Objects.requireNonNull(
                selectedConfiguration, "selectedConfiguration");
        }
    }

    record Pair(
        EvolutionFinalTestMeasurement baseline,
        EvolutionFinalTestMeasurement selected
    ) {
        public Pair {
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(selected, "selected");
        }
    }
}
