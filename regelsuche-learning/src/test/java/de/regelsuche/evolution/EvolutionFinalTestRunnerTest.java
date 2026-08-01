package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvolutionFinalTestRunnerTest {
    @Test
    void reservesBeforeEvaluatorAndPersistsCompleteEvidence(
        @TempDir Path tempDir
    ) throws Exception {
        EvolutionValidationSelection selection = selectedValidation("primary");
        EvolutionFinalTestSuite suite = suite("primary");
        FileEvolutionFinalTestAttemptStore delegate =
            new FileEvolutionFinalTestAttemptStore(tempDir);
        AtomicBoolean reserved = new AtomicBoolean();
        EvolutionFinalTestAttemptStore store = new EvolutionFinalTestAttemptStore() {
            @Override
            public void reserve(EvolutionFinalTestReservation reservation)
                    throws IOException {
                delegate.reserve(reservation);
                reserved.set(true);
            }

            @Override
            public void writeEvaluation(EvolutionFinalTestEvaluation evaluation)
                    throws IOException {
                delegate.writeEvaluation(evaluation);
            }
        };

        EvolutionFinalTestEvaluation result = new EvolutionFinalTestRunner()
            .executeOnce(selection, suite, (definition, context) -> {
                assertTrue(reserved.get());
                assertEquals(selection.selectedGenomeHash(),
                    context.selectedGenomeHash());
                assertEquals(suite.baselineProfileHash(),
                    context.baselineProfileHash());
                assertEquals(5,
                    context.selectedConfiguration().maxDepth());
                return pair(false, true, EvolutionCorrectnessStatus.CONFIRMED);
            }, store);

        assertEquals(2, result.cases().size());
        assertEquals(2, result.newlySolvedCases());
        assertEquals(EvolutionFinalTestEvaluation.ExecutionOutcome.COMPLETED,
            result.executionOutcome());
        assertTrue(result.qualificationEligible());
        assertTrue(Files.isRegularFile(
            delegate.reservationPath(result.runIdentity())));
        assertTrue(Files.isRegularFile(
            delegate.evaluationPath(result.runIdentity())));
    }

    @Test
    void secondAttemptAndReplacementSelectionOrSuiteAreRejectedBeforeEvaluation(
        @TempDir Path tempDir
    ) throws Exception {
        EvolutionFinalTestRunner runner = new EvolutionFinalTestRunner();
        FileEvolutionFinalTestAttemptStore store =
            new FileEvolutionFinalTestAttemptStore(tempDir);
        runner.executeOnce(selectedValidation("first"), suite("first"),
            (definition, context) -> pair(
                false, false, EvolutionCorrectnessStatus.NOT_EVALUATED), store);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(EvolutionFinalTestAlreadyReservedException.class, () ->
            runner.executeOnce(selectedValidation("replacement"),
                suite("replacement"), (definition, context) -> {
                    calls.incrementAndGet();
                    return pair(true, true,
                        EvolutionCorrectnessStatus.CONFIRMED);
                }, new FileEvolutionFinalTestAttemptStore(tempDir)));
        assertEquals(0, calls.get());
    }

    @Test
    void evaluatorFailureIsRetainedAndStillConsumesAttempt(
        @TempDir Path tempDir
    ) throws Exception {
        EvolutionValidationSelection selection = selectedValidation("failure");
        EvolutionFinalTestSuite suite = suite("failure");
        FileEvolutionFinalTestAttemptStore store =
            new FileEvolutionFinalTestAttemptStore(tempDir);

        EvolutionFinalTestEvaluation result = new EvolutionFinalTestRunner()
            .executeOnce(selection, suite,
                (definition, context) -> {
                    throw new IllegalStateException("deliberate");
                }, store);

        assertEquals(2, result.failedCaseEvaluations());
        assertFalse(result.qualificationEligible());
        assertEquals(
            EvolutionFinalTestEvaluation.ExecutionOutcome.COMPLETED_WITH_FAILURES,
            result.executionOutcome());
        assertThrows(EvolutionFinalTestAlreadyReservedException.class, () ->
            new EvolutionFinalTestRunner().executeOnce(selection, suite,
                (definition, context) -> pair(
                    true, true, EvolutionCorrectnessStatus.CONFIRMED),
                new FileEvolutionFinalTestAttemptStore(tempDir)));
    }

    @Test
    void failedResultWriteDoesNotRestoreTheConsumedAttempt(
        @TempDir Path tempDir
    ) throws Exception {
        EvolutionValidationSelection selection = selectedValidation("write");
        EvolutionFinalTestSuite suite = suite("write");
        FileEvolutionFinalTestAttemptStore delegate =
            new FileEvolutionFinalTestAttemptStore(tempDir);
        EvolutionFinalTestAttemptStore failing = new EvolutionFinalTestAttemptStore() {
            @Override
            public void reserve(EvolutionFinalTestReservation reservation)
                    throws IOException {
                delegate.reserve(reservation);
            }

            @Override
            public void writeEvaluation(EvolutionFinalTestEvaluation evaluation)
                    throws IOException {
                throw new IOException("deliberate result failure");
            }
        };

        assertThrows(IOException.class, () ->
            new EvolutionFinalTestRunner().executeOnce(selection, suite,
                (definition, context) -> pair(
                    false, false, EvolutionCorrectnessStatus.NOT_EVALUATED),
                failing));
        assertThrows(EvolutionFinalTestAlreadyReservedException.class, () ->
            new EvolutionFinalTestRunner().executeOnce(selection, suite,
                (definition, context) -> pair(
                    true, true, EvolutionCorrectnessStatus.CONFIRMED),
                new FileEvolutionFinalTestAttemptStore(tempDir)));
    }

    @Test
    void resultFromReplacementReservationCannotUseExistingAttempt(
        @TempDir Path tempDir
    ) throws Exception {
        EvolutionValidationSelection first = selectedValidation("first-binding");
        EvolutionValidationSelection replacement =
            selectedValidation("replacement-binding");
        EvolutionFinalTestSuite suite = suite("binding");
        EvolutionFinalTestReservation firstReservation =
            EvolutionFinalTestReservation.create(first, suite);
        EvolutionFinalTestReservation replacementReservation =
            EvolutionFinalTestReservation.create(replacement, suite);
        FileEvolutionFinalTestAttemptStore store =
            new FileEvolutionFinalTestAttemptStore(tempDir);
        store.reserve(firstReservation);
        List<EvolutionFinalTestCaseEvidence> cases = suite.cases().stream()
            .map(definition -> EvolutionFinalTestCaseEvidence.create(
                definition,
                measurement(false, EvolutionCorrectnessStatus.NOT_EVALUATED),
                measurement(false, EvolutionCorrectnessStatus.NOT_EVALUATED)))
            .toList();
        EvolutionFinalTestEvaluation replacementResult =
            EvolutionFinalTestEvaluation.create(
                replacementReservation, suite, cases);

        assertThrows(IOException.class,
            () -> store.writeEvaluation(replacementResult));
        assertFalse(Files.exists(
            store.evaluationPath(firstReservation.runIdentity())));
    }

    @Test
    void noEligibleValidationSelectionCannotConsumeFinalTest(
        @TempDir Path tempDir
    ) throws IOException {
        EvolutionValidationCandidate blocked = candidate(
            "blocked", List.of(validationEvidence(false, false)),
            List.of("NO_ELIGIBLE_CONFIGURATION"));
        EvolutionValidationSelection selection = EvolutionValidationSelection.create(
            hash("study"), hash("split"), hash("train"), hash("validation"),
            List.of("validation-a"), List.of(blocked));

        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestRunner().executeOnce(selection, suite("none"),
                (definition, context) -> pair(
                    false, false, EvolutionCorrectnessStatus.NOT_EVALUATED),
                new FileEvolutionFinalTestAttemptStore(tempDir)));
        try (var files = Files.list(tempDir)) {
            assertTrue(files.findAny().isEmpty());
        }
    }

    private static EvolutionValidationSelection selectedValidation(String id) {
        return EvolutionValidationSelection.create(
            hash("study"), hash("split"), hash("train-" + id),
            hash("validation-suite-" + id), List.of("validation-a"),
            List.of(candidate(id, List.of(validationEvidence(false, true)),
                List.of())));
    }

    private static EvolutionValidationCandidate candidate(
        String id,
        List<EvolutionValidationCaseEvidence> cases,
        List<String> blockers
    ) {
        return EvolutionValidationCandidate.create(
            hash("genome-" + id), hash("structure-" + id),
            new EvolutionValidationSearchConfiguration(5, 200, 12),
            cases, blockers);
    }

    private static EvolutionValidationCaseEvidence validationEvidence(
        boolean baselineReached,
        boolean selectedReached
    ) {
        EvolutionCorrectnessStatus baseline = baselineReached
            ? EvolutionCorrectnessStatus.CONFIRMED
            : EvolutionCorrectnessStatus.NOT_EVALUATED;
        EvolutionCorrectnessStatus selected = selectedReached
            ? EvolutionCorrectnessStatus.CONFIRMED
            : EvolutionCorrectnessStatus.NOT_EVALUATED;
        return new EvolutionValidationCaseEvidence(
            "validation-a", "family", baselineReached, selectedReached,
            baseline, selected,
            baselineReached ? "TARGET_REACHED" : "FRONTIER_EXHAUSTED",
            selectedReached ? "TARGET_REACHED" : "FRONTIER_EXHAUSTED",
            baselineReached ? 3 : -1, selectedReached ? 3 : -1,
            10, 10, 5, 5,
            !baselineReached && selectedReached,
            baselineReached && !selectedReached, false, false);
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

    private static EvolutionFinalTestCaseEvaluator.Pair pair(
        boolean baselineReached,
        boolean selectedReached,
        EvolutionCorrectnessStatus selectedCorrectness
    ) {
        return new EvolutionFinalTestCaseEvaluator.Pair(
            measurement(baselineReached,
                baselineReached ? EvolutionCorrectnessStatus.CONFIRMED
                    : EvolutionCorrectnessStatus.NOT_EVALUATED),
            measurement(selectedReached, selectedCorrectness));
    }

    private static EvolutionFinalTestMeasurement measurement(
        boolean reached,
        EvolutionCorrectnessStatus correctness
    ) {
        return new EvolutionFinalTestMeasurement(
            EvolutionFinalTestMeasurement.Status.COMPLETED, reached,
            reached ? "TARGET_REACHED" : "FRONTIER_EXHAUSTED",
            reached ? 3 : -1, 10, 5, correctness,
            reached ? hash("result-" + correctness) : "");
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }
}
