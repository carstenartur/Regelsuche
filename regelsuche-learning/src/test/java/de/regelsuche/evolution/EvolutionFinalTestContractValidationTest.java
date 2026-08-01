package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvolutionFinalTestContractValidationTest {
    private static final String STUDY = hash("study");
    private static final String SPLIT = hash("split");

    @Test
    void measurementRejectsEveryInconsistentState() {
        assertThrows(NullPointerException.class, () ->
            new EvolutionFinalTestMeasurement(
                null, false, "reason", -1, 0, 0,
                EvolutionCorrectnessStatus.NOT_EVALUATED, ""));
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestMeasurement(
                EvolutionFinalTestMeasurement.Status.COMPLETED,
                false, " ", -1, 0, 0,
                EvolutionCorrectnessStatus.NOT_EVALUATED, ""));
        assertThrows(NullPointerException.class, () ->
            new EvolutionFinalTestMeasurement(
                EvolutionFinalTestMeasurement.Status.COMPLETED,
                false, "reason", -1, 0, 0, null, ""));
        assertThrows(IllegalArgumentException.class, () ->
            completed(false, -2, 0, 0,
                EvolutionCorrectnessStatus.NOT_EVALUATED, ""));
        assertThrows(IllegalArgumentException.class, () ->
            completed(false, -1, -1, 0,
                EvolutionCorrectnessStatus.NOT_EVALUATED, ""));
        assertThrows(IllegalArgumentException.class, () ->
            completed(false, -1, 0, -1,
                EvolutionCorrectnessStatus.NOT_EVALUATED, ""));
        assertThrows(IllegalArgumentException.class, () ->
            completed(false, -1, 0, 0,
                EvolutionCorrectnessStatus.NOT_EVALUATED, "not-a-hash"));

        assertThrows(IllegalArgumentException.class, () -> failed(
            true, -1, EvolutionCorrectnessStatus.NOT_EVALUATED, ""));
        assertThrows(IllegalArgumentException.class, () -> failed(
            false, 0, EvolutionCorrectnessStatus.NOT_EVALUATED, ""));
        assertThrows(IllegalArgumentException.class, () -> failed(
            false, -1, EvolutionCorrectnessStatus.CONFIRMED, ""));
        assertThrows(IllegalArgumentException.class, () -> failed(
            false, -1, EvolutionCorrectnessStatus.NOT_EVALUATED,
            hash("unexpected-result")));

        assertThrows(IllegalArgumentException.class, () -> completed(
            true, -1, 0, 0, EvolutionCorrectnessStatus.CONFIRMED,
            hash("result")));
        assertThrows(IllegalArgumentException.class, () -> completed(
            false, 0, 0, 0, EvolutionCorrectnessStatus.NOT_EVALUATED, ""));
        assertThrows(IllegalArgumentException.class, () -> completed(
            true, 1, 0, 0, EvolutionCorrectnessStatus.NOT_EVALUATED,
            hash("result")));
        assertThrows(IllegalArgumentException.class, () -> completed(
            false, -1, 0, 0, EvolutionCorrectnessStatus.CONFIRMED, ""));
        assertThrows(IllegalArgumentException.class, () -> completed(
            true, 1, 0, 0, EvolutionCorrectnessStatus.CONFIRMED, ""));
    }

    @Test
    void measurementSupportsTheThreeRetainedResultShapes() {
        EvolutionFinalTestMeasurement unreached = measurement(
            false, EvolutionCorrectnessStatus.NOT_EVALUATED);
        EvolutionFinalTestMeasurement reached = measurement(
            true, EvolutionCorrectnessStatus.INCONCLUSIVE);
        EvolutionFinalTestMeasurement failed =
            EvolutionFinalTestMeasurement.failed("TECHNICAL_FAILURE");

        assertEquals(EvolutionFinalTestMeasurement.Status.COMPLETED,
            unreached.status());
        assertEquals(hash("result-INCONCLUSIVE"),
            reached.resultArtifactHash());
        assertEquals(EvolutionFinalTestMeasurement.Status.FAILED,
            failed.status());
        assertEquals("", failed.resultArtifactHash());
    }

    @Test
    void caseEvidenceRejectsEveryDerivedFlagSubstitution() {
        EvolutionFinalTestSuite.CaseDefinition definition = definition("case");
        EvolutionFinalTestCaseEvidence newlySolved =
            EvolutionFinalTestCaseEvidence.create(
                definition,
                measurement(false, EvolutionCorrectnessStatus.NOT_EVALUATED),
                measurement(true, EvolutionCorrectnessStatus.CONFIRMED));
        assertDerivedFlagTamperingRejected(newlySolved,
            false, newlySolved.reachabilityRegression(),
            newlySolved.correctnessFailure(),
            newlySolved.correctnessRegression(),
            newlySolved.evaluationFailed());

        EvolutionFinalTestCaseEvidence reachabilityRegression =
            EvolutionFinalTestCaseEvidence.create(
                definition,
                measurement(true, EvolutionCorrectnessStatus.CONFIRMED),
                measurement(false, EvolutionCorrectnessStatus.NOT_EVALUATED));
        assertDerivedFlagTamperingRejected(reachabilityRegression,
            reachabilityRegression.newlySolved(), false,
            reachabilityRegression.correctnessFailure(),
            reachabilityRegression.correctnessRegression(),
            reachabilityRegression.evaluationFailed());

        EvolutionFinalTestCaseEvidence correctnessRegression =
            EvolutionFinalTestCaseEvidence.create(
                definition,
                measurement(true, EvolutionCorrectnessStatus.CONFIRMED),
                measurement(true, EvolutionCorrectnessStatus.REFUTED));
        assertDerivedFlagTamperingRejected(correctnessRegression,
            correctnessRegression.newlySolved(),
            correctnessRegression.reachabilityRegression(), false,
            correctnessRegression.correctnessRegression(),
            correctnessRegression.evaluationFailed());
        assertDerivedFlagTamperingRejected(correctnessRegression,
            correctnessRegression.newlySolved(),
            correctnessRegression.reachabilityRegression(),
            correctnessRegression.correctnessFailure(), false,
            correctnessRegression.evaluationFailed());

        EvolutionFinalTestCaseEvidence technicalFailure =
            EvolutionFinalTestCaseEvidence.create(
                definition,
                EvolutionFinalTestMeasurement.failed("BASELINE_FAILED"),
                measurement(false, EvolutionCorrectnessStatus.NOT_EVALUATED));
        assertDerivedFlagTamperingRejected(technicalFailure,
            technicalFailure.newlySolved(),
            technicalFailure.reachabilityRegression(),
            technicalFailure.correctnessFailure(),
            technicalFailure.correctnessRegression(), false);

        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestCaseEvidence(
                " ", "family", technicalFailure.baseline(),
                technicalFailure.selected(), false, false, false, false, true));
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestCaseEvidence(
                "case", " ", technicalFailure.baseline(),
                technicalFailure.selected(), false, false, false, false, true));
        assertThrows(NullPointerException.class, () ->
            new EvolutionFinalTestCaseEvidence(
                "case", "family", null, technicalFailure.selected(),
                false, false, false, false, true));
        assertThrows(NullPointerException.class, () ->
            new EvolutionFinalTestCaseEvidence(
                "case", "family", technicalFailure.baseline(), null,
                false, false, false, false, true));
    }

    @Test
    void suiteRejectsSchemaSplitCaseAndHashTampering() {
        EvolutionFinalTestSuite valid = suite("suite", 2);
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestSuite(
                "unknown", valid.studyPlanHash(), valid.splitManifestHash(),
                valid.baselineProfileHash(), valid.evaluationSplit(),
                valid.cases(), valid.contentHash()));
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestSuite(
                valid.schema(), valid.studyPlanHash(), valid.splitManifestHash(),
                valid.baselineProfileHash(), "VALIDATION",
                valid.cases(), valid.contentHash()));
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionFinalTestSuite.create(
                STUDY, SPLIT, hash("baseline"), List.of()));
        assertThrows(NullPointerException.class, () ->
            EvolutionFinalTestSuite.create(
                STUDY, SPLIT, hash("baseline"),
                java.util.Arrays.asList((EvolutionFinalTestSuite.CaseDefinition) null)));
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestSuite(
                valid.schema(), valid.studyPlanHash(), valid.splitManifestHash(),
                valid.baselineProfileHash(), valid.evaluationSplit(),
                valid.cases(), hash("wrong-content")));
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestSuite.CaseDefinition(
                " ", "family", hash("material")));
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestSuite.CaseDefinition(
                "case", " ", hash("material")));
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestSuite.CaseDefinition(
                "case", "family", "invalid"));
        assertThrows(IllegalArgumentException.class,
            () -> EvolutionFinalTestSuite.fromCanonicalJson(" "));
        assertThrows(IllegalArgumentException.class,
            () -> EvolutionFinalTestSuite.fromCanonicalJson("{"));
    }

    @Test
    void reservationRejectsReplacementAndSerializationTampering() {
        EvolutionValidationSelection selection = selection("reservation");
        EvolutionFinalTestSuite suite = suite("reservation", 1);
        EvolutionFinalTestReservation valid =
            EvolutionFinalTestReservation.create(selection, suite);

        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestReservation(
                "unknown", valid.runIdentity(), valid.studyPlanHash(),
                valid.splitManifestHash(), valid.validationSelectionHash(),
                valid.finalTestSuiteHash(), valid.selectedGenomeHash(),
                valid.selectedConfigurationHash(), valid.finalTestStatus(),
                valid.contentHash()));
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestReservation(
                valid.schema(), hash("other-run"), valid.studyPlanHash(),
                valid.splitManifestHash(), valid.validationSelectionHash(),
                valid.finalTestSuiteHash(), valid.selectedGenomeHash(),
                valid.selectedConfigurationHash(), valid.finalTestStatus(),
                valid.contentHash()));
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestReservation(
                valid.schema(), valid.runIdentity(), valid.studyPlanHash(),
                valid.splitManifestHash(), valid.validationSelectionHash(),
                valid.finalTestSuiteHash(), valid.selectedGenomeHash(),
                valid.selectedConfigurationHash(), "COMPLETED",
                valid.contentHash()));
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestReservation(
                valid.schema(), valid.runIdentity(), valid.studyPlanHash(),
                valid.splitManifestHash(), valid.validationSelectionHash(),
                valid.finalTestSuiteHash(), valid.selectedGenomeHash(),
                valid.selectedConfigurationHash(), valid.finalTestStatus(),
                hash("wrong-content")));
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionFinalTestReservation.create(
                noEligibleSelection(), suite("none", 1)));
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionFinalTestReservation.create(
                selection, EvolutionFinalTestSuite.create(
                    hash("other-study"), SPLIT, hash("baseline"),
                    List.of(definition("case")))));
        assertThrows(IllegalArgumentException.class,
            () -> EvolutionFinalTestReservation.fromCanonicalJson(" "));
        assertThrows(IllegalArgumentException.class,
            () -> EvolutionFinalTestReservation.fromCanonicalJson("{"));

        String canonical = valid.toCanonicalJson().trim();
        String unknown = canonical.substring(0, canonical.length() - 1)
            + ",\"unknown\":true}";
        assertThrows(IllegalArgumentException.class,
            () -> EvolutionFinalTestReservation.fromCanonicalJson(unknown));
    }

    @Test
    void evaluationRejectsIdentityMatrixAggregateOutcomeAndStatusTampering() {
        EvolutionFinalTestEvaluation valid = evaluation(
            "valid", List.of(pair(false, false)), 1);

        EvaluationCopy copy = new EvaluationCopy(valid);
        copy.schema = "unknown";
        assertThrows(IllegalArgumentException.class, copy::build);

        copy = new EvaluationCopy(valid);
        copy.runIdentity = hash("other-run");
        assertThrows(IllegalArgumentException.class, copy::build);

        copy = new EvaluationCopy(valid);
        copy.evaluationSplit = "VALIDATION";
        assertThrows(IllegalArgumentException.class, copy::build);

        copy = new EvaluationCopy(valid);
        copy.finalTestCaseIds = List.of();
        assertThrows(IllegalArgumentException.class, copy::build);

        copy = new EvaluationCopy(valid);
        copy.cases = List.of();
        assertThrows(IllegalArgumentException.class, copy::build);

        copy = new EvaluationCopy(valid);
        copy.finalTestCaseIds = List.of("different");
        assertThrows(IllegalArgumentException.class, copy::build);

        copy = new EvaluationCopy(valid);
        copy.baselineReachedCases++;
        assertThrows(IllegalArgumentException.class, copy::build);
        copy = new EvaluationCopy(valid);
        copy.selectedReachedCases++;
        assertThrows(IllegalArgumentException.class, copy::build);
        copy = new EvaluationCopy(valid);
        copy.newlySolvedCases++;
        assertThrows(IllegalArgumentException.class, copy::build);
        copy = new EvaluationCopy(valid);
        copy.reachabilityRegressions++;
        assertThrows(IllegalArgumentException.class, copy::build);
        copy = new EvaluationCopy(valid);
        copy.correctnessFailures++;
        assertThrows(IllegalArgumentException.class, copy::build);
        copy = new EvaluationCopy(valid);
        copy.correctnessRegressions++;
        assertThrows(IllegalArgumentException.class, copy::build);
        copy = new EvaluationCopy(valid);
        copy.failedCaseEvaluations++;
        assertThrows(IllegalArgumentException.class, copy::build);

        copy = new EvaluationCopy(valid);
        copy.executionOutcome = null;
        assertThrows(NullPointerException.class, copy::build);
        copy = new EvaluationCopy(valid);
        copy.executionOutcome =
            EvolutionFinalTestEvaluation.ExecutionOutcome.COMPLETED_WITH_FAILURES;
        assertThrows(IllegalArgumentException.class, copy::build);

        copy = new EvaluationCopy(valid);
        copy.finalTestStatus = "RESERVED";
        assertThrows(IllegalArgumentException.class, copy::build);
        assertDownstreamStatusRejected(valid, "proof");
        assertDownstreamStatusRejected(valid, "novelty");
        assertDownstreamStatusRejected(valid, "promotion");
        assertDownstreamStatusRejected(valid, "public");

        copy = new EvaluationCopy(valid);
        copy.contentHash = hash("wrong-content");
        assertThrows(IllegalArgumentException.class, copy::build);
    }

    @Test
    void evaluationDerivesEveryExecutionOutcomeAndEligibilityState() {
        EvolutionFinalTestEvaluation completed = evaluation(
            "completed", List.of(pair(false, true)), 1);
        EvolutionFinalTestEvaluation failed = evaluation(
            "failed", List.of(new EvolutionFinalTestCaseEvaluator.Pair(
                EvolutionFinalTestMeasurement.failed("baseline-failed"),
                EvolutionFinalTestMeasurement.failed("selected-failed"))), 1);
        EvolutionFinalTestEvaluation blocked = evaluation(
            "blocked", List.of(pair(true, false)), 1);
        EvolutionFinalTestEvaluation combined = evaluation(
            "combined", List.of(
                pair(true, false),
                new EvolutionFinalTestCaseEvaluator.Pair(
                    EvolutionFinalTestMeasurement.failed("baseline-failed"),
                    EvolutionFinalTestMeasurement.failed("selected-failed"))), 2);
        EvolutionFinalTestEvaluation correctnessBlocked = evaluation(
            "refuted", List.of(pair(
                true, true, EvolutionCorrectnessStatus.REFUTED)), 1);

        assertEquals(EvolutionFinalTestEvaluation.ExecutionOutcome.COMPLETED,
            completed.executionOutcome());
        assertEquals(
            EvolutionFinalTestEvaluation.ExecutionOutcome.COMPLETED_WITH_FAILURES,
            failed.executionOutcome());
        assertEquals(
            EvolutionFinalTestEvaluation.ExecutionOutcome.COMPLETED_WITH_QUALITY_BLOCKERS,
            blocked.executionOutcome());
        assertEquals(
            EvolutionFinalTestEvaluation.ExecutionOutcome
                .COMPLETED_WITH_FAILURES_AND_QUALITY_BLOCKERS,
            combined.executionOutcome());
        assertTrue(completed.qualificationEligible());
        assertFalse(failed.qualificationEligible());
        assertFalse(blocked.qualificationEligible());
        assertFalse(correctnessBlocked.qualificationEligible());

        assertThrows(IllegalArgumentException.class, () ->
            EvolutionFinalTestEvaluation.create(
                EvolutionFinalTestReservation.create(
                    selection("mismatch"), suite("mismatch", 1)),
                suite("other-suite", 1),
                List.of(EvolutionFinalTestCaseEvidence.create(
                    definition("case-0"),
                    measurement(false,
                        EvolutionCorrectnessStatus.NOT_EVALUATED),
                    measurement(false,
                        EvolutionCorrectnessStatus.NOT_EVALUATED)))));
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionFinalTestEvaluation.create(
                reservation("count", 2), suite("count", 2),
                List.of(EvolutionFinalTestCaseEvidence.create(
                    definition("case-0"),
                    measurement(false,
                        EvolutionCorrectnessStatus.NOT_EVALUATED),
                    measurement(false,
                        EvolutionCorrectnessStatus.NOT_EVALUATED)))));
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionFinalTestEvaluation.create(
                reservation("family", 1), suite("family", 1),
                List.of(new EvolutionFinalTestCaseEvidence(
                    "case-0", "wrong-family",
                    measurement(false,
                        EvolutionCorrectnessStatus.NOT_EVALUATED),
                    measurement(false,
                        EvolutionCorrectnessStatus.NOT_EVALUATED),
                    false, false, false, false, false))));
    }

    @Test
    void evaluationJsonRejectsBlankMalformedAndUnknownInput() {
        EvolutionFinalTestEvaluation valid = evaluation(
            "json", List.of(pair(false, false)), 1);
        assertThrows(IllegalArgumentException.class,
            () -> EvolutionFinalTestEvaluation.fromCanonicalJson(" "));
        assertThrows(IllegalArgumentException.class,
            () -> EvolutionFinalTestEvaluation.fromCanonicalJson("{"));
        String canonical = valid.toCanonicalJson().trim();
        String unknown = canonical.substring(0, canonical.length() - 1)
            + ",\"unknown\":true}";
        assertThrows(IllegalArgumentException.class,
            () -> EvolutionFinalTestEvaluation.fromCanonicalJson(unknown));
    }

    @Test
    void fileStoreRejectsMissingDuplicateTamperedAndUnwritableAttempts(
        @TempDir Path tempDir
    ) throws Exception {
        EvolutionFinalTestReservation reservation = reservation("store", 1);
        EvolutionFinalTestEvaluation evaluation = evaluation(
            "store", List.of(pair(false, false)), 1);
        FileEvolutionFinalTestAttemptStore store =
            new FileEvolutionFinalTestAttemptStore(tempDir);

        assertThrows(NullPointerException.class,
            () -> new FileEvolutionFinalTestAttemptStore(null));
        assertThrows(NullPointerException.class, () -> store.reserve(null));
        assertThrows(NullPointerException.class,
            () -> store.writeEvaluation(null));
        assertThrows(IOException.class, () -> store.writeEvaluation(evaluation));

        store.reserve(reservation);
        EvolutionFinalTestAlreadyReservedException duplicateReservation =
            assertThrows(EvolutionFinalTestAlreadyReservedException.class,
                () -> store.reserve(reservation));
        assertTrue(duplicateReservation.getMessage().contains(
            reservation.runIdentity()));
        store.writeEvaluation(evaluation);
        assertThrows(EvolutionFinalTestAlreadyReservedException.class,
            () -> store.writeEvaluation(evaluation));

        Path malformedRoot = tempDir.resolve("malformed");
        FileEvolutionFinalTestAttemptStore malformed =
            new FileEvolutionFinalTestAttemptStore(malformedRoot);
        Files.createDirectories(malformedRoot);
        Files.writeString(
            malformed.reservationPath(reservation.runIdentity()), "{",
            StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
            () -> malformed.writeEvaluation(evaluation));

        Path regularFile = tempDir.resolve("not-a-directory");
        Files.writeString(regularFile, "x", StandardCharsets.UTF_8);
        assertThrows(IOException.class,
            () -> new FileEvolutionFinalTestAttemptStore(regularFile)
                .reserve(reservation));
    }

    @Test
    void runnerRejectsNullsAndRetainsInterruptAsTechnicalFailure(
        @TempDir Path tempDir
    ) throws Exception {
        EvolutionFinalTestRunner runner = new EvolutionFinalTestRunner();
        EvolutionValidationSelection selection = selection("runner");
        EvolutionFinalTestSuite suite = suite("runner", 1);
        EvolutionFinalTestCaseEvaluator evaluator =
            (definition, context) -> pair(false, false);
        EvolutionFinalTestAttemptStore store =
            new FileEvolutionFinalTestAttemptStore(tempDir.resolve("normal"));

        assertThrows(NullPointerException.class,
            () -> runner.executeOnce(null, suite, evaluator, store));
        assertThrows(NullPointerException.class,
            () -> runner.executeOnce(selection, null, evaluator, store));
        assertThrows(NullPointerException.class,
            () -> runner.executeOnce(selection, suite, null, store));
        assertThrows(NullPointerException.class,
            () -> runner.executeOnce(selection, suite, evaluator, null));

        AtomicInteger calls = new AtomicInteger();
        EvolutionFinalTestEvaluation interrupted = runner.executeOnce(
            selection("interrupted"), suite("interrupted", 1),
            (definition, context) -> {
                calls.incrementAndGet();
                throw new InterruptedException("deliberate");
            }, new FileEvolutionFinalTestAttemptStore(
                tempDir.resolve("interrupted")));
        try {
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, calls.get());
            assertEquals(1, interrupted.failedCaseEvaluations());
            assertTrue(interrupted.cases().getFirst()
                .baseline().terminalReason()
                .contains("InterruptedException"));
        } finally {
            Thread.interrupted();
        }

        EvolutionFinalTestEvaluation nullPair = runner.executeOnce(
            selection("null-pair"), suite("null-pair", 1),
            (definition, context) -> null,
            new FileEvolutionFinalTestAttemptStore(
                tempDir.resolve("null-pair")));
        assertEquals(1, nullPair.failedCaseEvaluations());
        assertTrue(nullPair.cases().getFirst().baseline().terminalReason()
            .contains("NullPointerException"));
    }

    @Test
    void evaluatorValueObjectsRejectIncompleteBindings() {
        EvolutionValidationSearchConfiguration configuration =
            new EvolutionValidationSearchConfiguration(1, 1, 1);
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestCaseEvaluator.EvaluationContext(
                "invalid", hash("genome"), configuration));
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestCaseEvaluator.EvaluationContext(
                hash("baseline"), "invalid", configuration));
        assertThrows(NullPointerException.class, () ->
            new EvolutionFinalTestCaseEvaluator.EvaluationContext(
                hash("baseline"), hash("genome"), null));
        assertThrows(NullPointerException.class, () ->
            new EvolutionFinalTestCaseEvaluator.Pair(
                null, measurement(false,
                    EvolutionCorrectnessStatus.NOT_EVALUATED)));
        assertThrows(NullPointerException.class, () ->
            new EvolutionFinalTestCaseEvaluator.Pair(
                measurement(false,
                    EvolutionCorrectnessStatus.NOT_EVALUATED), null));

        EvolutionFinalTestAlreadyReservedException exception =
            new EvolutionFinalTestAlreadyReservedException("already used");
        assertInstanceOf(IOException.class, exception);
        assertEquals("already used", exception.getMessage());
    }

    private static void assertDerivedFlagTamperingRejected(
        EvolutionFinalTestCaseEvidence valid,
        boolean newlySolved,
        boolean reachabilityRegression,
        boolean correctnessFailure,
        boolean correctnessRegression,
        boolean evaluationFailed
    ) {
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionFinalTestCaseEvidence(
                valid.caseId(), valid.family(), valid.baseline(),
                valid.selected(), newlySolved, reachabilityRegression,
                correctnessFailure, correctnessRegression,
                evaluationFailed));
    }

    private static void assertDownstreamStatusRejected(
        EvolutionFinalTestEvaluation valid,
        String field
    ) {
        EvaluationCopy copy = new EvaluationCopy(valid);
        switch (field) {
            case "proof" -> copy.proofStatus = "COMPLETED";
            case "novelty" -> copy.externalNoveltyStatus = "COMPLETED";
            case "promotion" -> copy.promotionStatus = "COMPLETED";
            case "public" -> copy.publicEvidenceStatus = "COMPLETED";
            default -> throw new IllegalArgumentException(field);
        }
        assertThrows(IllegalArgumentException.class, copy::build);
    }

    private static EvolutionFinalTestEvaluation evaluation(
        String id,
        List<EvolutionFinalTestCaseEvaluator.Pair> pairs,
        int caseCount
    ) {
        EvolutionFinalTestSuite suite = suite(id, caseCount);
        EvolutionFinalTestReservation reservation =
            EvolutionFinalTestReservation.create(selection(id), suite);
        List<EvolutionFinalTestCaseEvidence> evidence =
            java.util.stream.IntStream.range(0, caseCount)
                .mapToObj(index -> EvolutionFinalTestCaseEvidence.create(
                    suite.cases().get(index),
                    pairs.get(Math.min(index, pairs.size() - 1)).baseline(),
                    pairs.get(Math.min(index, pairs.size() - 1)).selected()))
                .toList();
        return EvolutionFinalTestEvaluation.create(
            reservation, suite, evidence);
    }

    private static EvolutionFinalTestReservation reservation(
        String id,
        int caseCount
    ) {
        return EvolutionFinalTestReservation.create(
            selection(id), suite(id, caseCount));
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
            STUDY, SPLIT, hash("train-" + id),
            hash("validation-suite-" + id), List.of("validation"),
            List.of(candidate));
    }

    private static EvolutionValidationSelection noEligibleSelection() {
        EvolutionValidationCaseEvidence validation =
            new EvolutionValidationCaseEvidence(
                "validation", "family", false, false,
                EvolutionCorrectnessStatus.NOT_EVALUATED,
                EvolutionCorrectnessStatus.NOT_EVALUATED,
                "FRONTIER_EXHAUSTED", "FRONTIER_EXHAUSTED", -1, -1,
                10, 10, 5, 5, false, false, false, false);
        EvolutionValidationCandidate candidate =
            EvolutionValidationCandidate.create(
                hash("blocked-genome"), hash("blocked-structure"),
                new EvolutionValidationSearchConfiguration(5, 200, 12),
                List.of(validation), List.of("BLOCKED"));
        return EvolutionValidationSelection.create(
            STUDY, SPLIT, hash("blocked-train"), hash("blocked-suite"),
            List.of("validation"), List.of(candidate));
    }

    private static EvolutionFinalTestSuite suite(String id, int caseCount) {
        return EvolutionFinalTestSuite.create(
            STUDY, SPLIT, hash("baseline-" + id),
            java.util.stream.IntStream.range(0, caseCount)
                .mapToObj(index -> new EvolutionFinalTestSuite.CaseDefinition(
                    "case-" + index, "family-" + index,
                    hash("material-" + id + "-" + index)))
                .toList());
    }

    private static EvolutionFinalTestSuite.CaseDefinition definition(
        String id
    ) {
        return new EvolutionFinalTestSuite.CaseDefinition(
            id, "family", hash("material-" + id));
    }

    private static EvolutionFinalTestCaseEvaluator.Pair pair(
        boolean baselineReached,
        boolean selectedReached
    ) {
        return pair(baselineReached, selectedReached,
            selectedReached ? EvolutionCorrectnessStatus.CONFIRMED
                : EvolutionCorrectnessStatus.NOT_EVALUATED);
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
        return completed(
            reached, reached ? 2 : -1, 10, 5, correctness,
            reached ? hash("result-" + correctness) : "");
    }

    private static EvolutionFinalTestMeasurement completed(
        boolean reached,
        int depth,
        long exploredStates,
        long candidateEvaluations,
        EvolutionCorrectnessStatus correctness,
        String resultArtifactHash
    ) {
        return new EvolutionFinalTestMeasurement(
            EvolutionFinalTestMeasurement.Status.COMPLETED,
            reached,
            reached ? "TARGET_REACHED" : "FRONTIER_EXHAUSTED",
            depth, exploredStates, candidateEvaluations,
            correctness, resultArtifactHash);
    }

    private static EvolutionFinalTestMeasurement failed(
        boolean reached,
        int depth,
        EvolutionCorrectnessStatus correctness,
        String resultArtifactHash
    ) {
        return new EvolutionFinalTestMeasurement(
            EvolutionFinalTestMeasurement.Status.FAILED,
            reached, "FAILED", depth, 0, 0,
            correctness, resultArtifactHash);
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }

    private static final class EvaluationCopy {
        String schema;
        String runIdentity;
        String studyPlanHash;
        String splitManifestHash;
        String validationSelectionHash;
        String finalTestSuiteHash;
        String reservationHash;
        String selectedGenomeHash;
        String selectedConfigurationHash;
        String evaluationSplit;
        List<String> finalTestCaseIds;
        List<EvolutionFinalTestCaseEvidence> cases;
        int baselineReachedCases;
        int selectedReachedCases;
        int newlySolvedCases;
        int reachabilityRegressions;
        int correctnessFailures;
        int correctnessRegressions;
        int failedCaseEvaluations;
        EvolutionFinalTestEvaluation.ExecutionOutcome executionOutcome;
        String finalTestStatus;
        String proofStatus;
        String externalNoveltyStatus;
        String promotionStatus;
        String publicEvidenceStatus;
        String contentHash;

        EvaluationCopy(EvolutionFinalTestEvaluation value) {
            schema = value.schema();
            runIdentity = value.runIdentity();
            studyPlanHash = value.studyPlanHash();
            splitManifestHash = value.splitManifestHash();
            validationSelectionHash = value.validationSelectionHash();
            finalTestSuiteHash = value.finalTestSuiteHash();
            reservationHash = value.reservationHash();
            selectedGenomeHash = value.selectedGenomeHash();
            selectedConfigurationHash = value.selectedConfigurationHash();
            evaluationSplit = value.evaluationSplit();
            finalTestCaseIds = value.finalTestCaseIds();
            cases = value.cases();
            baselineReachedCases = value.baselineReachedCases();
            selectedReachedCases = value.selectedReachedCases();
            newlySolvedCases = value.newlySolvedCases();
            reachabilityRegressions = value.reachabilityRegressions();
            correctnessFailures = value.correctnessFailures();
            correctnessRegressions = value.correctnessRegressions();
            failedCaseEvaluations = value.failedCaseEvaluations();
            executionOutcome = value.executionOutcome();
            finalTestStatus = value.finalTestStatus();
            proofStatus = value.proofStatus();
            externalNoveltyStatus = value.externalNoveltyStatus();
            promotionStatus = value.promotionStatus();
            publicEvidenceStatus = value.publicEvidenceStatus();
            contentHash = value.contentHash();
        }

        EvolutionFinalTestEvaluation build() {
            return new EvolutionFinalTestEvaluation(
                schema, runIdentity, studyPlanHash, splitManifestHash,
                validationSelectionHash, finalTestSuiteHash, reservationHash,
                selectedGenomeHash, selectedConfigurationHash,
                evaluationSplit, finalTestCaseIds, cases,
                baselineReachedCases, selectedReachedCases, newlySolvedCases,
                reachabilityRegressions, correctnessFailures,
                correctnessRegressions, failedCaseEvaluations,
                executionOutcome, finalTestStatus, proofStatus,
                externalNoveltyStatus, promotionStatus,
                publicEvidenceStatus, contentHash);
        }
    }
}
