package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.SafePreparationProductQualificationReport.CaseResult;
import de.regelsuche.benchmark.SafePreparationProductQualificationReport.ProductDecision;
import de.regelsuche.benchmark.SafePreparationProductQualificationReport.Report;
import de.regelsuche.benchmark.SafePreparationProductQualificationReport.RouteOutcome;
import de.regelsuche.benchmark.SafePreparationProductQualificationReport.SafeTelemetry;
import org.junit.jupiter.api.Test;

class SafePreparationProductQualificationExperimentTest {
    private static final String REVISION = "a".repeat(40);

    @Test
    void comparesDirectAndSafeUnderOneFrozenInformationParityBudget() {
        Report report = new SafePreparationProductQualificationExperiment()
            .run(REVISION);

        assertEquals(
            SafePreparationProductQualificationExperiment.DIRECT_PROFILE_ID,
            report.directProfileId());
        assertEquals(
            SafePreparationProductQualificationExperiment.SAFE_PROFILE_ID,
            report.safeProfileId());
        assertTrue(report.directRouteIsNoPreparationAblation());
        assertTrue(report.verificationReplayCharged());
        assertEquals(
            SafePreparationProductQualificationReport
                .SAFE_WORK_ACCOUNTING_REVISION,
            report.safeWorkAccountingRevision());
        assertTrue(report.evidenceQualified());
        assertTrue(report.newlyReachedCases() >= 1);
        assertTrue(report.commonSolvedCases() >= 1);
        assertTrue(report.cases().stream().allMatch(result ->
            result.direct().configuredBudget().equals(
                result.safe().configuredBudget())));
        assertTrue(report.cases().stream().allMatch(result ->
            result.direct().visibleInventoryFingerprint().equals(
                result.safe().visibleInventoryFingerprint())));
        assertFalse(report.productBlockers().isEmpty());
        assertEquals(
            ProductDecision.KEEP_OPT_IN_PENDING_PRODUCT_COVERAGE,
            report.productDecision());
    }

    @Test
    void chargesDeterministicVerificationReplayInsteadOfTreatingItAsFree() {
        Report report = new SafePreparationProductQualificationExperiment()
            .run(REVISION);

        assertTrue(report.cases().stream().allMatch(result -> {
            var telemetry = result.safe().safeTelemetry();
            return telemetry.coordinatorCalls() == telemetry.verificationCalls()
                && telemetry.analyzeMechanicalWork()
                    == telemetry.verificationReplayMechanicalWork()
                && telemetry.chargedCoordinatorMechanicalWork()
                    == telemetry.analyzeMechanicalWork()
                        + telemetry.verificationReplayMechanicalWork();
        }));
        assertTrue(report.cases().stream().anyMatch(result ->
            result.safe().safeTelemetry().chargedCoordinatorMechanicalWork()
                > 0));
    }

    @Test
    void preparedCapabilityDoesNotDiscountItsPrimitiveProofWork() {
        Report report = new SafePreparationProductQualificationExperiment()
            .run(REVISION);
        CaseResult exact = caseById(
            report,
            "perfect-square-native-exact-preparation");

        assertFalse(exact.direct().semanticReached());
        assertTrue(exact.safe().semanticReached());
        assertTrue(exact.newlyReachedBySafe());
        assertEquals(2, exact.safe().primitiveDepth());
        assertTrue(exact.safe().primitiveRuleIds().contains(
            "prepare_exact_monomial_square_structure"));
        assertTrue(exact.safe().primitiveRuleIds().contains(
            "ast_square_difference_factor"));
    }

    @Test
    void guardControlCannotBecomeAQualifiedSuccessWithoutAssumptions() {
        Report report = new SafePreparationProductQualificationExperiment()
            .run(REVISION);
        CaseResult guard = caseById(
            report,
            "telescoping-missing-guard-control");

        assertTrue(guard.direct().syntacticallyReached());
        assertFalse(guard.direct().semanticReached());
        assertEquals(
            guard.experimentCase().requiredAssumptions(),
            guard.direct().missingAssumptions());
        assertFalse(guard.safe().semanticReached());
        assertTrue(report.directSyntacticButSemanticallyRejectedCases() >= 1);
        assertEquals(0, report.safeSyntacticButSemanticallyRejectedCases());
    }

    @Test
    void technicalFailureOnEitherMatchedRouteCannotQualifyOrCreateAGain() {
        Report report = new SafePreparationProductQualificationExperiment()
            .run(REVISION);
        CaseResult exact = caseById(
            report,
            "perfect-square-native-exact-preparation");
        RouteOutcome failedDirect = RouteOutcome.technicalFailure(
            exact.direct().profileId(),
            exact.direct().visibleInventoryFingerprint(),
            exact.direct().configuredBudget(),
            "synthetic direct-route failure",
            SafeTelemetry.none());
        CaseResult failedPair = new CaseResult(
            exact.experimentCase(),
            failedDirect,
            exact.safe(),
            true,
            false,
            false,
            false,
            true);

        assertFalse(failedPair.expectationsSatisfied());
        assertFalse(failedPair.newlyReachedBySafe());

        Report failedReport = new Report(
            report.schema(),
            report.configurationId(),
            report.repositoryRevision(),
            report.directProfileId(),
            report.safeProfileId(),
            report.safeWorkAccountingRevision(),
            report.verificationReplayCharged(),
            report.visibleInventoryFingerprint(),
            report.principalInventoryFingerprint(),
            report.preparationInventoryFingerprint(),
            report.exactRegistryFingerprint(),
            report.preparationBudget(),
            report.searchBudget(),
            report.directRouteIsNoPreparationAblation(),
            report.cases().stream()
                .map(result -> result == exact ? failedPair : result)
                .toList(),
            report.productBlockers());

        assertFalse(failedReport.evidenceQualified());
        assertEquals(
            ProductDecision.KEEP_OPT_IN_SAFETY_FAILURE,
            failedReport.productDecision());
        assertEquals(
            report.newlyReachedCases() - 1,
            failedReport.newlyReachedCases());
    }

    @Test
    void safeProfileHasNoRegressionOnEveryCommonDirectSuccess() {
        Report report = new SafePreparationProductQualificationExperiment()
            .run(REVISION);

        assertTrue(report.cases().stream()
            .noneMatch(CaseResult::reachabilityRegression));
        assertTrue(report.cases().stream()
            .noneMatch(CaseResult::correctnessRegression));
        assertTrue(report.cases().stream()
            .noneMatch(CaseResult::assumptionRegression));
        assertTrue(report.cases().stream().allMatch(result ->
            result.safe().safeTelemetry().verificationFailures() == 0));
    }

    private static CaseResult caseById(Report report, String id) {
        return report.cases().stream()
            .filter(result -> result.experimentCase().id().equals(id))
            .findFirst()
            .orElseThrow();
    }
}
