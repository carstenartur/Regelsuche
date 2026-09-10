package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class SafePreparationProductQualificationExperimentTest {
    private static final String REVISION = "a".repeat(40);

    @Test
    void comparesDirectAndSafeUnderOneFrozenInformationParityBudget() {
        var report = new SafePreparationProductQualificationExperiment()
            .run(REVISION);

        assertEquals(
            SafePreparationProductQualificationExperiment.DIRECT_PROFILE_ID,
            report.directProfileId());
        assertEquals(
            SafePreparationProductQualificationExperiment.SAFE_PROFILE_ID,
            report.safeProfileId());
        assertTrue(report.directRouteIsNoPreparationAblation());
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
            SafePreparationProductQualificationExperiment.ProductDecision
                .KEEP_OPT_IN_PENDING_PRODUCT_COVERAGE,
            report.productDecision());
    }

    @Test
    void preparedCapabilityDoesNotDiscountItsPrimitiveProofWork() {
        var report = new SafePreparationProductQualificationExperiment()
            .run(REVISION);
        var exact = caseById(
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
        var report = new SafePreparationProductQualificationExperiment()
            .run(REVISION);
        var guard = caseById(
            report,
            "telescoping-missing-guard-control");

        assertTrue(guard.direct().syntacticallyReached());
        assertFalse(guard.direct().semanticReached());
        assertEquals(
            java.util.List.of("n + 1 != 0", "n != 0"),
            guard.direct().missingAssumptions());
        assertFalse(guard.safe().semanticReached());
        assertTrue(report.directSyntacticButSemanticallyRejectedCases() >= 1);
        assertEquals(0, report.safeSyntacticButSemanticallyRejectedCases());
    }

    @Test
    void safeProfileHasNoRegressionOnEveryCommonDirectSuccess() {
        var report = new SafePreparationProductQualificationExperiment()
            .run(REVISION);

        assertTrue(report.cases().stream()
            .noneMatch(SafePreparationProductQualificationExperiment
                .CaseResult::reachabilityRegression));
        assertTrue(report.cases().stream()
            .noneMatch(SafePreparationProductQualificationExperiment
                .CaseResult::correctnessRegression));
        assertTrue(report.cases().stream()
            .noneMatch(SafePreparationProductQualificationExperiment
                .CaseResult::assumptionRegression));
        assertTrue(report.cases().stream().allMatch(result ->
            result.safe().safeTelemetry().verificationFailures() == 0));
    }

    private static SafePreparationProductQualificationExperiment.CaseResult
            caseById(
        SafePreparationProductQualificationExperiment.Report report,
        String id
    ) {
        return report.cases().stream()
            .filter(result -> result.experimentCase().id().equals(id))
            .findFirst()
            .orElseThrow();
    }
}
