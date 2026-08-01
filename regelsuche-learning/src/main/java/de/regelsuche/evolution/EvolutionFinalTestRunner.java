package de.regelsuche.evolution;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reserves first, then evaluates every FINAL TEST case exactly once. */
public final class EvolutionFinalTestRunner {
    public EvolutionFinalTestEvaluation executeOnce(
        EvolutionValidationSelection selection,
        EvolutionFinalTestSuite suite,
        EvolutionFinalTestCaseEvaluator evaluator,
        EvolutionFinalTestAttemptStore store
    ) throws IOException {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(evaluator, "evaluator");
        Objects.requireNonNull(store, "store");
        EvolutionFinalTestReservation reservation =
            EvolutionFinalTestReservation.create(selection, suite);
        EvolutionValidationCandidate selected = selectedCandidate(selection);

        // The durable CREATE_NEW reservation happens before the evaluator sees
        // even the first FINAL TEST case definition.
        store.reserve(reservation);

        EvolutionFinalTestCaseEvaluator.EvaluationContext context =
            new EvolutionFinalTestCaseEvaluator.EvaluationContext(
                suite.baselineProfileHash(), selection.selectedGenomeHash(),
                selected.searchConfiguration());
        List<EvolutionFinalTestCaseEvidence> evidence = new ArrayList<>();
        for (EvolutionFinalTestSuite.CaseDefinition definition
                : suite.cases()) {
            EvolutionFinalTestCaseEvaluator.Pair pair;
            try {
                pair = Objects.requireNonNull(
                    evaluator.evaluate(definition, context),
                    "evaluator result");
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String reason = "EVALUATOR_EXCEPTION:"
                    + exception.getClass().getSimpleName();
                pair = new EvolutionFinalTestCaseEvaluator.Pair(
                    EvolutionFinalTestMeasurement.failed(reason),
                    EvolutionFinalTestMeasurement.failed(reason));
            }
            evidence.add(EvolutionFinalTestCaseEvidence.create(
                definition, pair.baseline(), pair.selected()));
        }
        EvolutionFinalTestEvaluation result =
            EvolutionFinalTestEvaluation.create(reservation, suite, evidence);
        store.writeEvaluation(result);
        return result;
    }

    private static EvolutionValidationCandidate selectedCandidate(
        EvolutionValidationSelection selection
    ) {
        return selection.candidates().stream()
            .filter(candidate -> candidate.configurationHash().equals(
                selection.selectedConfigurationHash()))
            .filter(candidate -> candidate.genomeHash().equals(
                selection.selectedGenomeHash()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "frozen selected configuration is absent"));
    }
}
