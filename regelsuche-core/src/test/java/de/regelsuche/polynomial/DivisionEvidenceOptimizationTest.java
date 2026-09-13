package de.regelsuche.polynomial;

import org.junit.jupiter.api.Test;

class DivisionEvidenceOptimizationTest {
    @Test
    void divisionReusesStageLabels() {
        DivisionEvidenceOptimizationChecks.divisionReusesStageLabels();
    }

    @Test
    void divisionDoesNotRescanTheKnownZeroSuffix() {
        DivisionEvidenceOptimizationChecks.divisionDoesNotRescanTheKnownZeroSuffix();
    }

    @Test
    void divisionMatchesEveryBudgetPrefix() {
        DivisionEvidenceOptimizationChecks.divisionMatchesEveryBudgetPrefix();
    }

    @Test
    void arbitraryFieldsKeepTheirDivisionAndFailureOrder() {
        DivisionEvidenceOptimizationChecks.arbitraryFieldsKeepTheirDivisionAndFailureOrder();
    }

    @Test
    void largePrimeInputsAndRepeatedDivisionRemainExact() {
        DivisionEvidenceOptimizationChecks.largePrimeInputsAndRepeatedDivisionRemainExact();
    }

    @Test
    void factorKeysAreSerializedOnce() {
        DivisionEvidenceOptimizationChecks.factorKeysAreSerializedOnce();
    }

    @Test
    void proposalKeysAreSerializedOnce() {
        DivisionEvidenceOptimizationChecks.proposalKeysAreSerializedOnce();
    }

    @Test
    void orderingMergingAndFirstDuplicateAreUnchanged() {
        DivisionEvidenceOptimizationChecks.orderingMergingAndFirstDuplicateAreUnchanged();
    }

}
