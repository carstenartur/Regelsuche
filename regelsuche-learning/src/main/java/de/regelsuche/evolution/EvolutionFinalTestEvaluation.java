package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable result of one consumed FINAL TEST attempt. */
public record EvolutionFinalTestEvaluation(
    String schema,
    String runIdentity,
    String studyPlanHash,
    String splitManifestHash,
    String validationSelectionHash,
    String finalTestSuiteHash,
    String reservationHash,
    String selectedGenomeHash,
    String selectedConfigurationHash,
    String evaluationSplit,
    List<String> finalTestCaseIds,
    List<EvolutionFinalTestCaseEvidence> cases,
    int baselineReachedCases,
    int selectedReachedCases,
    int newlySolvedCases,
    int reachabilityRegressions,
    int correctnessFailures,
    int correctnessRegressions,
    int failedCaseEvaluations,
    ExecutionOutcome executionOutcome,
    String finalTestStatus,
    String proofStatus,
    String externalNoveltyStatus,
    String promotionStatus,
    String publicEvidenceStatus,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-final-test-evaluation/v1";
    public static final String COMPLETED = "COMPLETED";
    public static final String NOT_EVALUATED = "NOT_EVALUATED";

    public EvolutionFinalTestEvaluation {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported FINAL TEST evaluation schema");
        }
        requireHashes(runIdentity, studyPlanHash, splitManifestHash,
            validationSelectionHash, finalTestSuiteHash, reservationHash,
            selectedGenomeHash, selectedConfigurationHash, contentHash);
        if (!EvolutionFinalTestReservation.runIdentity(
                studyPlanHash, splitManifestHash).equals(runIdentity)) {
            throw new IllegalArgumentException(
                "FINAL TEST evaluation runIdentity mismatch");
        }
        if (!EvolutionFinalTestSuite.FINAL_TEST.equals(evaluationSplit)) {
            throw new IllegalArgumentException(
                "evaluationSplit must be FINAL_TEST");
        }
        finalTestCaseIds = canonicalIds(finalTestCaseIds);
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException(
                "FINAL TEST evaluation requires case evidence");
        }
        cases = List.copyOf(cases);
        List<String> actualIds = cases.stream()
            .map(EvolutionFinalTestCaseEvidence::caseId).toList();
        if (!finalTestCaseIds.equals(actualIds)) {
            throw new IllegalArgumentException(
                "FINAL TEST evidence does not retain the frozen case order");
        }
        int actualBaselineReached = count(cases,
            evidence -> evidence.baseline().reached());
        int actualSelectedReached = count(cases,
            evidence -> evidence.selected().reached());
        int actualNewlySolved = count(cases,
            EvolutionFinalTestCaseEvidence::newlySolved);
        int actualReachabilityRegressions = count(cases,
            EvolutionFinalTestCaseEvidence::reachabilityRegression);
        int actualCorrectnessFailures = count(cases,
            EvolutionFinalTestCaseEvidence::correctnessFailure);
        int actualCorrectnessRegressions = count(cases,
            EvolutionFinalTestCaseEvidence::correctnessRegression);
        int actualFailures = count(cases,
            EvolutionFinalTestCaseEvidence::evaluationFailed);
        if (baselineReachedCases != actualBaselineReached
                || selectedReachedCases != actualSelectedReached
                || newlySolvedCases != actualNewlySolved
                || reachabilityRegressions != actualReachabilityRegressions
                || correctnessFailures != actualCorrectnessFailures
                || correctnessRegressions != actualCorrectnessRegressions
                || failedCaseEvaluations != actualFailures) {
            throw new IllegalArgumentException(
                "FINAL TEST aggregates differ from case evidence");
        }
        Objects.requireNonNull(executionOutcome, "executionOutcome");
        ExecutionOutcome expectedOutcome = outcome(
            actualFailures, actualReachabilityRegressions,
            actualCorrectnessFailures);
        if (executionOutcome != expectedOutcome) {
            throw new IllegalArgumentException(
                "FINAL TEST executionOutcome mismatch");
        }
        if (!COMPLETED.equals(finalTestStatus)) {
            throw new IllegalArgumentException(
                "consumed FINAL TEST must be marked COMPLETED");
        }
        requireNotEvaluated(proofStatus, "proofStatus");
        requireNotEvaluated(
            externalNoveltyStatus, "externalNoveltyStatus");
        requireNotEvaluated(promotionStatus, "promotionStatus");
        requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
        if (!EvolutionValidationArtifactSupport.hash(payload(
                runIdentity, studyPlanHash, splitManifestHash,
                validationSelectionHash, finalTestSuiteHash, reservationHash,
                selectedGenomeHash, selectedConfigurationHash,
                evaluationSplit, finalTestCaseIds, cases,
                baselineReachedCases, selectedReachedCases, newlySolvedCases,
                reachabilityRegressions, correctnessFailures,
                correctnessRegressions, failedCaseEvaluations,
                executionOutcome, finalTestStatus, proofStatus,
                externalNoveltyStatus, promotionStatus,
                publicEvidenceStatus)).equals(contentHash)) {
            throw new IllegalArgumentException(
                "FINAL TEST evaluation contentHash mismatch");
        }
    }

    public static EvolutionFinalTestEvaluation create(
        EvolutionFinalTestReservation reservation,
        EvolutionFinalTestSuite suite,
        List<EvolutionFinalTestCaseEvidence> cases
    ) {
        if (!reservation.finalTestSuiteHash().equals(suite.contentHash())
                || !reservation.studyPlanHash().equals(suite.studyPlanHash())
                || !reservation.splitManifestHash().equals(
                    suite.splitManifestHash())) {
            throw new IllegalArgumentException(
                "FINAL TEST evaluation does not match its reservation");
        }
        List<EvolutionFinalTestCaseEvidence> retained = List.copyOf(cases);
        requireSuiteEvidenceMatch(suite, retained);
        List<String> ids = suite.cases().stream()
            .map(EvolutionFinalTestSuite.CaseDefinition::caseId).toList();
        int baselineReached = count(retained,
            evidence -> evidence.baseline().reached());
        int selectedReached = count(retained,
            evidence -> evidence.selected().reached());
        int newlySolved = count(retained,
            EvolutionFinalTestCaseEvidence::newlySolved);
        int reachabilityRegressions = count(retained,
            EvolutionFinalTestCaseEvidence::reachabilityRegression);
        int correctnessFailures = count(retained,
            EvolutionFinalTestCaseEvidence::correctnessFailure);
        int correctnessRegressions = count(retained,
            EvolutionFinalTestCaseEvidence::correctnessRegression);
        int failures = count(retained,
            EvolutionFinalTestCaseEvidence::evaluationFailed);
        ExecutionOutcome executionOutcome = outcome(
            failures, reachabilityRegressions, correctnessFailures);
        Map<String, Object> payload = payload(
            reservation.runIdentity(), reservation.studyPlanHash(),
            reservation.splitManifestHash(),
            reservation.validationSelectionHash(), suite.contentHash(),
            reservation.contentHash(), reservation.selectedGenomeHash(),
            reservation.selectedConfigurationHash(),
            EvolutionFinalTestSuite.FINAL_TEST, ids, retained,
            baselineReached, selectedReached, newlySolved,
            reachabilityRegressions, correctnessFailures,
            correctnessRegressions, failures, executionOutcome, COMPLETED,
            NOT_EVALUATED, NOT_EVALUATED, NOT_EVALUATED, NOT_EVALUATED);
        return new EvolutionFinalTestEvaluation(
            SCHEMA, reservation.runIdentity(), reservation.studyPlanHash(),
            reservation.splitManifestHash(),
            reservation.validationSelectionHash(), suite.contentHash(),
            reservation.contentHash(), reservation.selectedGenomeHash(),
            reservation.selectedConfigurationHash(),
            EvolutionFinalTestSuite.FINAL_TEST, ids, retained,
            baselineReached, selectedReached, newlySolved,
            reachabilityRegressions, correctnessFailures,
            correctnessRegressions, failures, executionOutcome, COMPLETED,
            NOT_EVALUATED, NOT_EVALUATED, NOT_EVALUATED, NOT_EVALUATED,
            EvolutionValidationArtifactSupport.hash(payload));
    }

    public boolean qualificationEligible() {
        return failedCaseEvaluations == 0
            && reachabilityRegressions == 0
            && correctnessFailures == 0;
    }

    boolean matchesReservation(EvolutionFinalTestReservation reservation) {
        return reservation != null
            && runIdentity.equals(reservation.runIdentity())
            && studyPlanHash.equals(reservation.studyPlanHash())
            && splitManifestHash.equals(reservation.splitManifestHash())
            && validationSelectionHash.equals(
                reservation.validationSelectionHash())
            && finalTestSuiteHash.equals(reservation.finalTestSuiteHash())
            && reservationHash.equals(reservation.contentHash())
            && selectedGenomeHash.equals(reservation.selectedGenomeHash())
            && selectedConfigurationHash.equals(
                reservation.selectedConfigurationHash());
    }

    public static EvolutionFinalTestEvaluation fromCanonicalJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                "FINAL TEST evaluation JSON must not be blank");
        }
        try {
            return EvolutionValidationArtifactSupport.JSON.readValue(
                json, EvolutionFinalTestEvaluation.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid FINAL TEST evaluation JSON", exception);
        }
    }

    public String toCanonicalJson() {
        try {
            Map<String, Object> value = payload(
                runIdentity, studyPlanHash, splitManifestHash,
                validationSelectionHash, finalTestSuiteHash, reservationHash,
                selectedGenomeHash, selectedConfigurationHash,
                evaluationSplit, finalTestCaseIds, cases,
                baselineReachedCases, selectedReachedCases, newlySolvedCases,
                reachabilityRegressions, correctnessFailures,
                correctnessRegressions, failedCaseEvaluations,
                executionOutcome, finalTestStatus, proofStatus,
                externalNoveltyStatus, promotionStatus, publicEvidenceStatus);
            value.put("schema", SCHEMA);
            value.put("contentHash", contentHash);
            return EvolutionValidationArtifactSupport.JSON
                .writeValueAsString(value) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "cannot serialize FINAL TEST evaluation", exception);
        }
    }

    public enum ExecutionOutcome {
        COMPLETED,
        COMPLETED_WITH_FAILURES,
        COMPLETED_WITH_QUALITY_BLOCKERS,
        COMPLETED_WITH_FAILURES_AND_QUALITY_BLOCKERS
    }

    private interface EvidencePredicate {
        boolean test(EvolutionFinalTestCaseEvidence evidence);
    }

    private static int count(
        List<EvolutionFinalTestCaseEvidence> values,
        EvidencePredicate predicate
    ) {
        return Math.toIntExact(values.stream().filter(predicate::test).count());
    }

    private static ExecutionOutcome outcome(
        int failures,
        int reachabilityRegressions,
        int correctnessFailures
    ) {
        boolean failed = failures > 0;
        boolean blocked = reachabilityRegressions > 0
            || correctnessFailures > 0;
        if (failed && blocked) {
            return ExecutionOutcome.COMPLETED_WITH_FAILURES_AND_QUALITY_BLOCKERS;
        }
        if (failed) {
            return ExecutionOutcome.COMPLETED_WITH_FAILURES;
        }
        if (blocked) {
            return ExecutionOutcome.COMPLETED_WITH_QUALITY_BLOCKERS;
        }
        return ExecutionOutcome.COMPLETED;
    }

    private static void requireSuiteEvidenceMatch(
        EvolutionFinalTestSuite suite,
        List<EvolutionFinalTestCaseEvidence> evidence
    ) {
        if (suite.cases().size() != evidence.size()) {
            throw new IllegalArgumentException(
                "FINAL TEST evidence count differs from the frozen suite");
        }
        for (int index = 0; index < evidence.size(); index++) {
            EvolutionFinalTestSuite.CaseDefinition definition =
                suite.cases().get(index);
            EvolutionFinalTestCaseEvidence retained = evidence.get(index);
            if (!definition.caseId().equals(retained.caseId())
                    || !definition.family().equals(retained.family())) {
                throw new IllegalArgumentException(
                    "FINAL TEST evidence differs from the frozen suite at index "
                        + index);
            }
        }
    }

    private static List<String> canonicalIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                "FINAL TEST evaluation requires case ids");
        }
        List<String> retained = values.stream()
            .map(value -> EvolutionValidationArtifactSupport.requireText(
                value, "finalTestCaseId"))
            .toList();
        if (retained.stream().distinct().count() != retained.size()) {
            throw new IllegalArgumentException(
                "FINAL TEST evaluation contains duplicate case ids");
        }
        return retained;
    }

    private static void requireHashes(String... values) {
        String[] fields = {
            "runIdentity", "studyPlanHash", "splitManifestHash",
            "validationSelectionHash", "finalTestSuiteHash",
            "reservationHash", "selectedGenomeHash",
            "selectedConfigurationHash", "contentHash"
        };
        for (int index = 0; index < fields.length; index++) {
            EvolutionGenome.requireSha256(values[index], fields[index]);
        }
    }

    private static void requireNotEvaluated(String value, String field) {
        if (!NOT_EVALUATED.equals(value)) {
            throw new IllegalArgumentException(
                field + " must remain NOT_EVALUATED");
        }
    }

    private static Map<String, Object> payload(
        String runIdentity,
        String studyPlanHash,
        String splitManifestHash,
        String validationSelectionHash,
        String finalTestSuiteHash,
        String reservationHash,
        String selectedGenomeHash,
        String selectedConfigurationHash,
        String evaluationSplit,
        List<String> finalTestCaseIds,
        List<EvolutionFinalTestCaseEvidence> cases,
        int baselineReachedCases,
        int selectedReachedCases,
        int newlySolvedCases,
        int reachabilityRegressions,
        int correctnessFailures,
        int correctnessRegressions,
        int failedCaseEvaluations,
        ExecutionOutcome executionOutcome,
        String finalTestStatus,
        String proofStatus,
        String externalNoveltyStatus,
        String promotionStatus,
        String publicEvidenceStatus
    ) {
        Map<String, Object> value = new TreeMap<>();
        value.put("baselineReachedCases", baselineReachedCases);
        value.put("cases", cases);
        value.put("correctnessFailures", correctnessFailures);
        value.put("correctnessRegressions", correctnessRegressions);
        value.put("evaluationSplit", evaluationSplit);
        value.put("executionOutcome", executionOutcome);
        value.put("externalNoveltyStatus", externalNoveltyStatus);
        value.put("failedCaseEvaluations", failedCaseEvaluations);
        value.put("finalTestCaseIds", finalTestCaseIds);
        value.put("finalTestStatus", finalTestStatus);
        value.put("finalTestSuiteHash", finalTestSuiteHash);
        value.put("newlySolvedCases", newlySolvedCases);
        value.put("promotionStatus", promotionStatus);
        value.put("proofStatus", proofStatus);
        value.put("publicEvidenceStatus", publicEvidenceStatus);
        value.put("reachabilityRegressions", reachabilityRegressions);
        value.put("reservationHash", reservationHash);
        value.put("runIdentity", runIdentity);
        value.put("selectedConfigurationHash", selectedConfigurationHash);
        value.put("selectedGenomeHash", selectedGenomeHash);
        value.put("selectedReachedCases", selectedReachedCases);
        value.put("splitManifestHash", splitManifestHash);
        value.put("studyPlanHash", studyPlanHash);
        value.put("validationSelectionHash", validationSelectionHash);
        return value;
    }
}
