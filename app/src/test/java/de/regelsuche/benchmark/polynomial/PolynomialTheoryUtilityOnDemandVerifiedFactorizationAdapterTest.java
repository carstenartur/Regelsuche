package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionOutcome.CacheDisposition;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapterTest {
    @Test
    void factorsTheIntegerRootCaseWithCompleteMeasuredLineage() {
        var measured = execute(
            "z02-difference-of-squares",
            "CP06_FULL"
        );
        var result = measured.result();

        assertEquals(
            TerminalStatus.VALIDATED_TRANSITION,
            result.terminalStatus()
        );
        assertPolicyBound(result);
        assertEquals(1, result.transitions().size());
        assertEquals(1, measured.measurements().transitionTraces().size());
        assertEquals(
            1,
            measured.measurements().factorizationAttempts().size()
        );
        assertTrue(result.work().factorizationWork() > 0L);
        assertTrue(result.work().verificationWork() > 0L);
        assertTrue(result.work().occurrenceReplacementWork() > 0L);
        assertTrue(result.work().evidenceConstructionWork() > 0L);
        assertEquals(0L, result.work().cacheLookupWork());
        assertEquals(
            CacheDisposition.CACHE_DISABLED,
            result.transitions().getFirst().cacheDisposition()
        );
        assertEquals(
            List.of(
                "EXECUTION_ADMISSION_POLICY",
                "OCCURRENCE_ROOT_SELECTION",
                "EXACT_SOURCE_EVIDENCE",
                "EXACT_FACTORIZATION_PIPELINE",
                "VERIFIER_SELECTED_CANDIDATE",
                "EXACT_FACTOR_RENDERING",
                "EXACT_REPARSE",
                "EXACT_POLYNOMIAL_RECONSTRUCTION",
                "VERIFIER_BOUND_TRANSFORMATION",
                "AST_OCCURRENCE_REPLACEMENT",
                "AST_REPLACEMENT_REPLAY"
            ),
            measured.measurements().primitiveRuleIds()
        );
        assertEquals(
            PolynomialTheoryUtilityOnDemandAdmissionPolicy.freeze().policyId(),
            measured.measurements().transitionTraces().getFirst()
                .primitiveSteps().getFirst().evidenceHash()
        );
    }

    @Test
    void factorsTheRationalCoefficientCaseNatively() {
        var measured = execute(
            "q02-rational-linear-factors",
            "CP06_FULL"
        );

        assertEquals(
            TerminalStatus.VALIDATED_TRANSITION,
            measured.result().terminalStatus()
        );
        assertEquals(
            "regelsuche.factorization.native-univariate-rational/v1",
            measured.measurements().factorizationAttempts().getFirst()
                .backendId()
        );
    }

    @Test
    void retainsANegativeVerifierReportForAnIrreducibleCase() {
        var measured = execute("q02-no-rational-root", "CP06_FULL");

        assertEquals(
            TerminalStatus.NO_TRANSITION,
            measured.result().terminalStatus()
        );
        assertPolicyBound(measured.result());
        assertEquals(
            1,
            measured.measurements().factorizationAttempts().size()
        );
        assertFalse(
            measured.measurements().factorizationAttempts().getFirst()
                .selectedCandidate()
        );
        assertTrue(measured.result().transitions().isEmpty());
    }

    @Test
    void rejectsTheMultivariateNearMissWithoutBackendSubstitution() {
        assertUnsupported("near-miss-multivariate");
    }

    @Test
    void rejectsTheRationalFunctionWithoutBackendSubstitution() {
        assertUnsupported("unsupported-rational-function");
    }

    @Test
    void rejectsTheSymbolicExponentWithoutBackendSubstitution() {
        assertUnsupported("unsupported-symbolic-exponent");
    }

    @Test
    void transformsTheFrozenNestedRightOccurrence() {
        var measured = execute("nested-single-occurrence", "CP06_FULL");

        assertEquals(
            TerminalStatus.VALIDATED_TRANSITION,
            measured.result().terminalStatus()
        );
        assertEquals(
            List.of(List.of(1)),
            measured.result().transitions().stream()
                .map(PolynomialTheoryUtilityTransitionOutcome::occurrencePath)
                .toList()
        );
        assertEquals(
            2,
            measured.measurements().transitionTraces().getFirst().pathDepth()
        );
    }

    @Test
    void transformsBothFrozenSiblingOccurrencesInPathOrder() {
        var measured = execute(
            "two-identical-occurrences",
            "CP06_FULL"
        );

        assertEquals(
            TerminalStatus.VALIDATED_TRANSITION,
            measured.result().terminalStatus()
        );
        assertEquals(
            List.of(List.of(0), List.of(1)),
            measured.result().transitions().stream()
                .map(PolynomialTheoryUtilityTransitionOutcome::occurrencePath)
                .toList()
        );
        assertEquals(2, measured.measurements().transitionTraces().size());
        assertEquals(
            2,
            measured.measurements().factorizationAttempts().size()
        );
    }

    @Test
    void transformsAllFourFrozenLeafOccurrencesInPathOrder() {
        var measured = execute(
            "four-identical-occurrences",
            "CP06_FULL"
        );

        assertEquals(
            TerminalStatus.VALIDATED_TRANSITION,
            measured.result().terminalStatus()
        );
        assertEquals(
            List.of(
                List.of(0, 0),
                List.of(0, 1),
                List.of(1, 0),
                List.of(1, 1)
            ),
            measured.result().transitions().stream()
                .map(PolynomialTheoryUtilityTransitionOutcome::occurrencePath)
                .toList()
        );
        assertEquals(4, measured.measurements().transitionTraces().size());
        assertEquals(
            4,
            measured.measurements().factorizationAttempts().size()
        );
    }

    @Test
    void failsClosedBeforeExecutingTheTinyBudgetCase() {
        assertBudgetBeforeExecution("z08-tiny-budget", "CP06_FULL");
    }

    @Test
    void failsClosedForAScaledCheckpointBeforeNativeExecution() {
        assertBudgetBeforeExecution(
            "z02-difference-of-squares",
            "CP01_1_OF_12"
        );
    }

    private static void assertUnsupported(String caseId) {
        var measured = execute(caseId, "CP06_FULL");

        assertEquals(
            TerminalStatus.UNSUPPORTED,
            measured.result().terminalStatus()
        );
        assertPolicyBound(measured.result());
        assertTrue(measured.measurements().factorizationAttempts().isEmpty());
        assertTrue(measured.result().transitions().isEmpty());
    }

    private static void assertBudgetBeforeExecution(
        String caseId,
        String checkpointId
    ) {
        var measured = execute(caseId, checkpointId);

        assertEquals(
            TerminalStatus.BUDGET_INCONCLUSIVE,
            measured.result().terminalStatus()
        );
        assertPolicyBound(measured.result());
        assertEquals(
            PolynomialTheoryUtilityWorkBreakdown.zero(),
            measured.result().work()
        );
        assertTrue(measured.measurements().factorizationAttempts().isEmpty());
        assertTrue(measured.measurements().transitionTraces().isEmpty());
    }

    private static void assertPolicyBound(
        PolynomialTheoryUtilityCandidateResult result
    ) {
        assertTrue(
            result.detailCode().endsWith(
                ':' + PolynomialTheoryUtilityOnDemandAdmissionPolicy.freeze()
                    .policyId()
            )
        );
    }

    private static PolynomialTheoryUtilityMeasuredCandidate execute(
        String caseId,
        String checkpointId
    ) {
        var formationCase = PolynomialTheoryUtilityCaseCorpus.load().cases()
            .stream()
            .filter(value -> caseId.equals(value.caseId()))
            .findFirst()
            .orElseThrow();
        var input = PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
            .stream()
            .filter(value ->
                PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                    .PROFILE_ID.equals(value.profileId())
            )
            .filter(value -> checkpointId.equals(value.checkpointId()))
            .filter(value -> caseId.equals(value.caseId()))
            .findFirst()
            .orElseThrow();
        return PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
            .executeCase(input, formationCase);
    }
}
