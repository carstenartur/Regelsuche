package de.regelsuche.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CandidateProofStatusTest {
    @Test
    void rejectedIsTheOnlyNonPositiveStatus() {
        assertFalse(CandidateProofStatus.REJECTED.isPositive());
        assertTrue(Arrays.stream(CandidateProofStatus.values())
                .filter(status -> status != CandidateProofStatus.REJECTED)
                .allMatch(CandidateProofStatus::isPositive));
    }

    @Test
    void proofThresholdsFollowTheDeclaredLifecycleOrder() {
        assertTrue(CandidateProofStatus.OBSERVED.atLeast(CandidateProofStatus.REJECTED));
        assertTrue(CandidateProofStatus.SYMBOLICALLY_VERIFIED
                .atLeast(CandidateProofStatus.VALIDATED_BY_EXAMPLES));
        assertTrue(CandidateProofStatus.FORMALLY_PROVED
                .atLeast(CandidateProofStatus.FORMALLY_PROVED));
        assertFalse(CandidateProofStatus.VALIDATED_BY_EXAMPLES
                .atLeast(CandidateProofStatus.SYMBOLICALLY_VERIFIED));
        assertFalse(CandidateProofStatus.REJECTED.atLeast(CandidateProofStatus.OBSERVED));
    }
}
