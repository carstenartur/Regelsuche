package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.OracleValidator;
import org.junit.jupiter.api.Test;

/**
 * Proves that intrinsic candidate validation runs the symbolic oracle for
 * every candidate, independent of whether it matches a historical
 * reference expression. {@link TargetFreeHeldOutQualifier} gates the
 * oracle behind reference matching, which leaves genuinely unknown
 * candidates permanently at {@code OBSERVED / NOT_RUN_REFERENCE_MISS};
 * this validator is the reference-independent alternative the open-target
 * slice requires.
 */
class TargetFreeIntrinsicCandidateValidatorTest {
    @Test
    void invokesOracleForNonReferenceCandidates() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.AGREE);

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x + x", "2 * x", true, oracle);

        assertTrue(oracle.invoked());
        assertEquals(
            CandidateProofStatus.SYMBOLICALLY_VERIFIED, validation.status());
        assertEquals("AGREE", validation.oracleStatus());
        assertTrue(validation.intrinsicallyVerified());
    }

    @Test
    void reportsDisagreementWithoutRequiringAReferenceMatch() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.DISAGREE);

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x + x", "3 * x", true, oracle);

        assertTrue(oracle.invoked());
        assertEquals(CandidateProofStatus.OBSERVED, validation.status());
        assertEquals("DISAGREE", validation.oracleStatus());
        assertFalse(validation.intrinsicallyVerified());
    }

    @Test
    void skipsOracleOnlyWhenCandidateIsNotClaimedEquivalencePreserving() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.AGREE);

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x + x", "2 * x", false, oracle);

        assertFalse(oracle.invoked());
        assertEquals(
            "NOT_EQUIVALENCE_PRESERVING_BY_CONSTRUCTION",
            validation.oracleStatus());
        assertFalse(validation.intrinsicallyVerified());
    }

    @Test
    void reportsValidatorErrorsWithoutThrowing() {
        OracleValidator failing = (left, right) -> {
            throw new IllegalStateException("boom");
        };

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x + x", "2 * x", true, failing);

        assertEquals(CandidateProofStatus.OBSERVED, validation.status());
        assertEquals(
            "VALIDATOR_ERROR_IllegalStateException",
            validation.oracleStatus());
    }

    @Test
    void rejectsNullArguments() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.AGREE);
        assertThrows(NullPointerException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                null, "2 * x", true, oracle));
        assertThrows(NullPointerException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                "x + x", null, true, oracle));
        assertThrows(NullPointerException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                "x + x", "2 * x", true, null));
    }

    private static final class FakeOracle implements OracleValidator {
        private final OracleValidationStatus status;
        private boolean invoked;

        private FakeOracle(OracleValidationStatus status) {
            this.status = status;
        }

        @Override
        public OracleValidation validateEquivalence(
            String leftExpression, String rightExpression) {
            invoked = true;
            return new OracleValidation(status, status.name());
        }

        boolean invoked() {
            return invoked;
        }
    }
}
