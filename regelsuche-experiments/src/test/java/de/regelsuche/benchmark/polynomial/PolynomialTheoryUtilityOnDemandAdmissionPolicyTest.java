package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityOnDemandAdmissionPolicyTest {
    @Test
    void derivesTheFrozenFloorsOnlyFromVisibleFullFormationRows() {
        var policy = PolynomialTheoryUtilityOnDemandAdmissionPolicy.freeze();

        assertEquals(
            PolynomialTheoryUtilityOnDemandAdmissionPolicy.SCHEMA,
            policy.schema()
        );
        assertEquals(
            PolynomialTheoryUtilityCaseCorpus.FORMATION_CONTENT_HASH,
            policy.formationContentHash()
        );
        assertEquals(
            PolynomialTheoryUtilityExecutionPlan.EXPECTED_CONTENT_HASH,
            policy.executionPlanContentHash()
        );
        assertEquals(
            PolynomialTheoryUtilityOnDemandAdmissionPolicy.CONTROL_EXCLUSION,
            policy.controlExclusion()
        );
        assertEquals(22, policy.eligibleOccurrenceCount());
        assertEquals(256L, policy.minimumMechanicalAuthority());
        assertEquals(16L, policy.minimumFactorizationAuthority());
        assertTrue(policy.policyId().matches("sha256:[0-9a-f]{64}"));
        assertEquals(
            policy,
            PolynomialTheoryUtilityOnDemandAdmissionPolicy.freeze()
        );
    }

    @Test
    void admitsTheFullRepeatedBoundaryAndRejectsScaledOrTinyRows() {
        var policy = PolynomialTheoryUtilityOnDemandAdmissionPolicy.freeze();
        var repeated = plan("four-identical-occurrences", "CP06_FULL");

        assertTrue(policy.admits(repeated));
        assertEquals(4, repeated.occurrences().size());
        repeated.occurrences().forEach(value -> {
            assertEquals(256L, value.totalMechanicalWork());
            assertEquals(16L, value.factorizationWork());
            assertTrue(policy.admits(value));
        });

        assertFalse(policy.admits(plan(
            "z02-difference-of-squares",
            "CP05_3_OF_4"
        )));
        assertFalse(policy.admits(plan("z08-tiny-budget", "CP06_FULL")));
    }

    @Test
    void rejectsARewrittenPolicyWithTheRetainedIdentity() {
        var policy = PolynomialTheoryUtilityOnDemandAdmissionPolicy.freeze();

        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityOnDemandAdmissionPolicy.Policy(
                policy.policyId(),
                policy.schema(),
                policy.formationContentHash(),
                policy.executionPlanContentHash(),
                policy.controlExclusion(),
                policy.eligibleOccurrenceCount(),
                policy.minimumMechanicalAuthority() + 1L,
                policy.minimumFactorizationAuthority()
            )
        );
    }

    private static PolynomialTheoryUtilityOnDemandOccurrencePlan plan(
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
                PolynomialTheoryUtilityOnDemandAdmissionPolicy.PROFILE_ID
                    .equals(value.profileId())
            )
            .filter(value -> checkpointId.equals(value.checkpointId()))
            .filter(value -> caseId.equals(value.caseId()))
            .findFirst()
            .orElseThrow();
        return PolynomialTheoryUtilityOnDemandOccurrencePlan.create(
            input,
            formationCase
        );
    }
}
