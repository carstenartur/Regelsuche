package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation
    .TargetFreeIntrinsicCandidateValidator.ValidationScope
        .CONDITIONAL_ASSUMPTIONS_NOT_EVALUATED;
import static de.regelsuche.discovery.representation
    .TargetFreeIntrinsicCandidateValidator.ValidationScope.NOT_APPLICABLE;
import static de.regelsuche.discovery.representation
    .TargetFreeIntrinsicCandidateValidator.ValidationScope.UNCONDITIONAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.OracleValidator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves that intrinsic candidate validation runs the symbolic oracle for
 * every equivalence-preserving candidate, independent of historical
 * reference matching, while retaining conditional validity as unresolved
 * when the available oracle cannot consume assumptions.
 */
class TargetFreeIntrinsicCandidateValidatorTest {
    @Test
    void invokesOracleForNonReferenceCandidates() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.AGREE);

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x + x", "2 * x", List.of(), true, oracle);

        assertTrue(oracle.invoked());
        assertEquals(
            CandidateProofStatus.SYMBOLICALLY_VERIFIED, validation.status());
        assertEquals("AGREE", validation.oracleStatus());
        assertEquals(UNCONDITIONAL, validation.scope());
        assertTrue(validation.assumptions().isEmpty());
        assertTrue(validation.intrinsicallyVerified());
        assertFalse(validation.conditionalValidityUnresolved());
    }

    @Test
    void unconditionalAgreementAlsoCoversListedAssumptions() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.AGREE);

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x / y",
            "x * (1 / y)",
            List.of("y != 0", " x != 0 ", "y != 0"),
            true,
            oracle
        );

        assertTrue(validation.intrinsicallyVerified());
        assertEquals(UNCONDITIONAL, validation.scope());
        assertEquals(List.of("x != 0", "y != 0"), validation.assumptions());
        assertFalse(validation.conditionalValidityUnresolved());
    }

    @Test
    void reportsUnconditionalDisagreementWithoutAReferenceMatch() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.DISAGREE);

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x + x", "3 * x", List.of(), true, oracle);

        assertTrue(oracle.invoked());
        assertEquals(CandidateProofStatus.OBSERVED, validation.status());
        assertEquals("DISAGREE", validation.oracleStatus());
        assertEquals(UNCONDITIONAL, validation.scope());
        assertFalse(validation.intrinsicallyVerified());
        assertFalse(validation.conditionalValidityUnresolved());
    }

    @Test
    void retainsConditionalValidityAfterUnconditionalDisagreement() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.DISAGREE);

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x / x",
            "1",
            List.of("x != 0"),
            true,
            oracle
        );

        assertTrue(oracle.invoked());
        assertEquals(CandidateProofStatus.OBSERVED, validation.status());
        assertEquals("DISAGREE", validation.oracleStatus());
        assertEquals(
            CONDITIONAL_ASSUMPTIONS_NOT_EVALUATED,
            validation.scope()
        );
        assertEquals(List.of("x != 0"), validation.assumptions());
        assertFalse(validation.intrinsicallyVerified());
        assertTrue(validation.conditionalValidityUnresolved());
    }

    @Test
    void retainsConditionalValidityWhenOracleIsUnavailable() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.UNAVAILABLE);

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x / y",
            "x * (1 / y)",
            List.of("y != 0"),
            true,
            oracle
        );

        assertEquals("UNAVAILABLE", validation.oracleStatus());
        assertEquals(
            CONDITIONAL_ASSUMPTIONS_NOT_EVALUATED,
            validation.scope()
        );
        assertTrue(validation.conditionalValidityUnresolved());
    }

    @Test
    void skipsOracleOnlyWhenCandidateIsNotClaimedEquivalencePreserving() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.AGREE);

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x + x",
            "2 * x",
            List.of("z != 0"),
            false,
            oracle
        );

        assertFalse(oracle.invoked());
        assertEquals(
            "NOT_RUN_NOT_EQUIVALENCE_PRESERVING_BY_CONSTRUCTION",
            validation.oracleStatus()
        );
        assertEquals(NOT_APPLICABLE, validation.scope());
        assertEquals(List.of("z != 0"), validation.assumptions());
        assertFalse(validation.intrinsicallyVerified());
        assertFalse(validation.conditionalValidityUnresolved());
    }

    @Test
    void reportsValidatorErrorsWithoutDroppingConditionalScope() {
        OracleValidator failing = (left, right) -> {
            throw new IllegalStateException("boom");
        };

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x / x", "1", List.of("x != 0"), true, failing);

        assertEquals(CandidateProofStatus.OBSERVED, validation.status());
        assertEquals(
            "VALIDATOR_ERROR_IllegalStateException",
            validation.oracleStatus()
        );
        assertEquals(
            CONDITIONAL_ASSUMPTIONS_NOT_EVALUATED,
            validation.scope()
        );
        assertTrue(validation.conditionalValidityUnresolved());
    }

    @Test
    void rejectsEvidenceThatSilentlyDropsConditionalUncertainty() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new TargetFreeIntrinsicCandidateValidator
                .IntrinsicValidation(
                    CandidateProofStatus.OBSERVED,
                    "DISAGREE",
                    UNCONDITIONAL,
                    List.of("x != 0")
                )
        );
    }

    @Test
    void rejectsNullOrBlankArguments() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.AGREE);
        assertThrows(NullPointerException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                null, "2 * x", List.of(), true, oracle));
        assertThrows(NullPointerException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                "x + x", null, List.of(), true, oracle));
        assertThrows(NullPointerException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                "x + x", "2 * x", null, true, oracle));
        assertThrows(NullPointerException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                "x + x", "2 * x", List.of(), true, null));
        assertThrows(IllegalArgumentException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                "x + x", "2 * x", List.of("  "), true, oracle));
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
