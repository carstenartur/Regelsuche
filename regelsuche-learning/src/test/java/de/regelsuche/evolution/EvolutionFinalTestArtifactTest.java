package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionFinalTestArtifactTest {
    @Test
    void suiteReservationAndEvaluationRoundTripCanonically() {
        EvolutionValidationSelection selection = selection("roundtrip");
        EvolutionFinalTestSuite suite = suite("roundtrip");
        EvolutionFinalTestReservation reservation =
            EvolutionFinalTestReservation.create(selection, suite);
        EvolutionFinalTestCaseEvidence evidence =
            EvolutionFinalTestCaseEvidence.create(suite.cases().getFirst(),
                measurement(false, EvolutionCorrectnessStatus.NOT_EVALUATED),
                measurement(true, EvolutionCorrectnessStatus.CONFIRMED));
        EvolutionFinalTestCaseEvidence second =
            EvolutionFinalTestCaseEvidence.create(suite.cases().getLast(),
                measurement(false, EvolutionCorrectnessStatus.NOT_EVALUATED),
                measurement(false, EvolutionCorrectnessStatus.NOT_EVALUATED));
        EvolutionFinalTestEvaluation evaluation =
            EvolutionFinalTestEvaluation.create(
                reservation, suite, List.of(evidence, second));

        assertEquals(suite, EvolutionFinalTestSuite.fromCanonicalJson(
            suite.toCanonicalJson()));
        assertEquals(reservation,
            EvolutionFinalTestReservation.fromCanonicalJson(
                reservation.toCanonicalJson()));
        assertEquals(evaluation,
            EvolutionFinalTestEvaluation.fromCanonicalJson(
                evaluation.toCanonicalJson()));
    }

    @Test
    void runIdentityCannotBeChangedByReplacingSelectionOrSuite() {
        EvolutionFinalTestReservation first = EvolutionFinalTestReservation.create(
            selection("first"), suite("first"));
        EvolutionFinalTestReservation replacement =
            EvolutionFinalTestReservation.create(
                selection("replacement"), suite("replacement"));

        assertEquals(first.runIdentity(), replacement.runIdentity());
    }

    @Test
    void duplicateSuiteCasesAndUnknownFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionFinalTestSuite.create(hash("study"), hash("split"),
                hash("baseline"), List.of(
                    new EvolutionFinalTestSuite.CaseDefinition(
                        "same", "family", hash("one")),
                    new EvolutionFinalTestSuite.CaseDefinition(
                        "same", "family", hash("two")))));

        EvolutionFinalTestSuite valid = suite("unknown");
        String canonical = valid.toCanonicalJson().trim();
        String unknown = canonical.substring(0, canonical.length() - 1)
            + ",\"unknown\":true}";
        assertThrows(IllegalArgumentException.class,
            () -> EvolutionFinalTestSuite.fromCanonicalJson(unknown));
    }

    @Test
    void technicalFailureIsNotMisclassifiedAsMathematicalRegression() {
        EvolutionFinalTestCaseEvidence evidence =
            EvolutionFinalTestCaseEvidence.create(
                new EvolutionFinalTestSuite.CaseDefinition(
                    "case", "family", hash("material")),
                measurement(true, EvolutionCorrectnessStatus.CONFIRMED),
                EvolutionFinalTestMeasurement.failed("INTERRUPTED"));

        assertTrue(evidence.evaluationFailed());
        assertFalse(evidence.newlySolved());
        assertFalse(evidence.reachabilityRegression());
        assertFalse(evidence.correctnessFailure());
        assertFalse(evidence.correctnessRegression());
    }

    @Test
    void failedBaselineCannotCreateAFalseNewlySolvedCase() {
        EvolutionFinalTestCaseEvidence evidence =
            EvolutionFinalTestCaseEvidence.create(
                new EvolutionFinalTestSuite.CaseDefinition(
                    "case", "family", hash("material")),
                EvolutionFinalTestMeasurement.failed("BASELINE_FAILED"),
                measurement(true, EvolutionCorrectnessStatus.CONFIRMED));

        assertTrue(evidence.evaluationFailed());
        assertFalse(evidence.newlySolved());
    }

    @Test
    void reachedMeasurementRequiresRetainedResultArtifact() {
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestMeasurement(
                EvolutionFinalTestMeasurement.Status.COMPLETED,
                true, "TARGET_REACHED", 2, 10, 5,
                EvolutionCorrectnessStatus.CONFIRMED, ""));
    }

    private static EvolutionValidationSelection selection(String id) {
        EvolutionValidationCaseEvidence validation =
            new EvolutionValidationCaseEvidence(
                "validation", "family", false, true,
                EvolutionCorrectnessStatus.NOT_EVALUATED,
                EvolutionCorrectnessStatus.CONFIRMED,
                "FRONTIER_EXHAUSTED", "TARGET_REACHED", -1, 2,
                10, 10, 5, 5, true, false, false, false);
        EvolutionValidationCandidate candidate =
            EvolutionValidationCandidate.create(
                hash("genome-" + id), hash("structure-" + id),
                new EvolutionValidationSearchConfiguration(5, 200, 12),
                List.of(validation), List.of());
        return EvolutionValidationSelection.create(
            hash("study"), hash("split"), hash("train-" + id),
            hash("validation-suite-" + id), List.of("validation"),
            List.of(candidate));
    }

    private static EvolutionFinalTestSuite suite(String id) {
        return EvolutionFinalTestSuite.create(
            hash("study"), hash("split"), hash("baseline-" + id),
            List.of(
                new EvolutionFinalTestSuite.CaseDefinition(
                    "final-a", "family-a", hash("case-a-" + id)),
                new EvolutionFinalTestSuite.CaseDefinition(
                    "final-b", "family-b", hash("case-b-" + id))));
    }

    private static EvolutionFinalTestMeasurement measurement(
        boolean reached,
        EvolutionCorrectnessStatus correctness
    ) {
        return new EvolutionFinalTestMeasurement(
            EvolutionFinalTestMeasurement.Status.COMPLETED, reached,
            reached ? "TARGET_REACHED" : "FRONTIER_EXHAUSTED",
            reached ? 2 : -1, 10, 5, correctness,
            reached ? hash("result-" + correctness) : "");
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }
}
