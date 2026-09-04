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
            OracleValidator.OracleValidationStatus.AGREE,
            "symbolic difference simplified to zero"
        );

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x + x", "2 * x", List.of(), true, oracle);

        assertTrue(oracle.invoked());
        assertEquals(
            CandidateProofStatus.SYMBOLICALLY_VERIFIED, validation.status());
        assertEquals("AGREE", validation.oracleStatus());
        assertEquals(
            "symbolic difference simplified to zero",
            validation.oracleEvidence()
        );
        assertEquals(UNCONDITIONAL, validation.scope());
        assertTrue(validation.assumptions().isEmpty());
        assertTrue(validation.intrinsicallyVerified());
        assertFalse(validation.intrinsicallyRejected());
        assertFalse(validation.conditionalValidityUnresolved());
    }

    @Test
    void unconditionalAgreementAlsoCoversCanonicalListedAssumptions() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.AGREE,
            "unconditional identity"
        );

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x / y",
            "x * (1 / y)",
            List.of(
                "0 != y",
                " x != 0 ",
                "x ≠ 0",
                "y != 0"),
            true,
            oracle
        );

        assertTrue(validation.intrinsicallyVerified());
        assertEquals("unconditional identity", validation.oracleEvidence());
        assertEquals(UNCONDITIONAL, validation.scope());
        assertEquals(List.of("x != 0", "y != 0"), validation.assumptions());
        assertFalse(validation.intrinsicallyRejected());
        assertFalse(validation.conditionalValidityUnresolved());
    }

    @Test
    void rejectsUnconditionalDisagreementWithoutAssumptions() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.DISAGREE,
            "counterexample x=1"
        );

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x + x", "3 * x", List.of(), true, oracle);

        assertTrue(oracle.invoked());
        assertEquals(CandidateProofStatus.REJECTED, validation.status());
        assertFalse(validation.status().isPositive());
        assertEquals("DISAGREE", validation.oracleStatus());
        assertEquals("counterexample x=1", validation.oracleEvidence());
        assertEquals(UNCONDITIONAL, validation.scope());
        assertFalse(validation.intrinsicallyVerified());
        assertTrue(validation.intrinsicallyRejected());
        assertFalse(validation.conditionalValidityUnresolved());
    }

    @Test
    void retainsConditionalValidityAfterUnconditionalDisagreement() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.DISAGREE,
            "unconditional counterexample x=0"
        );

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
            "unconditional counterexample x=0",
            validation.oracleEvidence()
        );
        assertEquals(
            CONDITIONAL_ASSUMPTIONS_NOT_EVALUATED,
            validation.scope()
        );
        assertEquals(List.of("x != 0"), validation.assumptions());
        assertFalse(validation.intrinsicallyVerified());
        assertFalse(validation.intrinsicallyRejected());
        assertTrue(validation.conditionalValidityUnresolved());
    }

    @Test
    void retainsConditionalValidityWhenOracleIsUnavailable() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.UNAVAILABLE,
            "backend not configured"
        );

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x / y",
            "x * (1 / y)",
            List.of("y != 0"),
            true,
            oracle
        );

        assertEquals(CandidateProofStatus.OBSERVED, validation.status());
        assertEquals("UNAVAILABLE", validation.oracleStatus());
        assertEquals("backend not configured", validation.oracleEvidence());
        assertEquals(
            CONDITIONAL_ASSUMPTIONS_NOT_EVALUATED,
            validation.scope()
        );
        assertFalse(validation.intrinsicallyRejected());
        assertTrue(validation.conditionalValidityUnresolved());
    }

    @Test
    void unavailableUnconditionalValidationRemainsObserved() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.UNAVAILABLE,
            "backend not configured"
        );

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x + x", "2 * x", List.of(), true, oracle);

        assertEquals(CandidateProofStatus.OBSERVED, validation.status());
        assertEquals(UNCONDITIONAL, validation.scope());
        assertFalse(validation.intrinsicallyVerified());
        assertFalse(validation.intrinsicallyRejected());
        assertFalse(validation.conditionalValidityUnresolved());
    }

    @Test
    void skipsOracleOnlyWhenCandidateIsNotClaimedEquivalencePreserving() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.AGREE,
            "must not be observed"
        );

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x + x",
            "2 * x",
            List.of("z != 0"),
            false,
            oracle
        );

        assertFalse(oracle.invoked());
        assertEquals(CandidateProofStatus.OBSERVED, validation.status());
        assertEquals(
            "NOT_RUN_NOT_EQUIVALENCE_PRESERVING_BY_CONSTRUCTION",
            validation.oracleStatus()
        );
        assertEquals(
            "The formation process did not claim source equivalence.",
            validation.oracleEvidence()
        );
        assertEquals(NOT_APPLICABLE, validation.scope());
        assertEquals(List.of("z != 0"), validation.assumptions());
        assertFalse(validation.intrinsicallyVerified());
        assertFalse(validation.intrinsicallyRejected());
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
            "java.lang.IllegalStateException: boom",
            validation.oracleEvidence()
        );
        assertEquals(
            CONDITIONAL_ASSUMPTIONS_NOT_EVALUATED,
            validation.scope()
        );
        assertFalse(validation.intrinsicallyRejected());
        assertTrue(validation.conditionalValidityUnresolved());
    }

    @Test
    void reportsNullOracleResultsAsUnavailableValidationErrors() {
        OracleValidator failing = (left, right) -> null;

        var validation = TargetFreeIntrinsicCandidateValidator.validate(
            "x + x", "2 * x", List.of(), true, failing);

        assertEquals(CandidateProofStatus.OBSERVED, validation.status());
        assertEquals(
            "VALIDATOR_ERROR_NullPointerException",
            validation.oracleStatus());
        assertTrue(validation.oracleEvidence().contains("oracle validation"));
        assertEquals(UNCONDITIONAL, validation.scope());
        assertFalse(validation.intrinsicallyRejected());
    }

    @Test
    void publicEvidenceCannotDowngradeAssumptionsFreeDisagreement() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new TargetFreeIntrinsicCandidateValidator
                .IntrinsicValidation(
                    CandidateProofStatus.OBSERVED,
                    "DISAGREE",
                    "counterexample x=1",
                    UNCONDITIONAL,
                    List.of()
                )
        );
    }

    @Test
    void rejectsEvidenceThatSilentlyDropsConditionalUncertainty() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new TargetFreeIntrinsicCandidateValidator
                .IntrinsicValidation(
                    CandidateProofStatus.OBSERVED,
                    "DISAGREE",
                    "counterexample x=0",
                    UNCONDITIONAL,
                    List.of("x != 0")
                )
        );
    }

    @Test
    void publicEvidenceCannotInventRejectionOrVerification() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new TargetFreeIntrinsicCandidateValidator
                .IntrinsicValidation(
                    CandidateProofStatus.REJECTED,
                    "UNAVAILABLE",
                    "backend unavailable",
                    UNCONDITIONAL,
                    List.of()
                )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TargetFreeIntrinsicCandidateValidator
                .IntrinsicValidation(
                    CandidateProofStatus.SYMBOLICALLY_VERIFIED,
                    "DISAGREE",
                    "counterexample",
                    UNCONDITIONAL,
                    List.of()
                )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TargetFreeIntrinsicCandidateValidator
                .IntrinsicValidation(
                    CandidateProofStatus.OBSERVED,
                    "AGREE",
                    "identity",
                    UNCONDITIONAL,
                    List.of()
                )
        );
    }

    @Test
    void rejectsNullEvidence() {
        assertThrows(
            NullPointerException.class,
            () -> new TargetFreeIntrinsicCandidateValidator
                .IntrinsicValidation(
                    CandidateProofStatus.OBSERVED,
                    "UNAVAILABLE",
                    null,
                    UNCONDITIONAL,
                    List.of()
                )
        );
    }

    @Test
    void rejectsNullOrBlankArguments() {
        FakeOracle oracle = new FakeOracle(
            OracleValidator.OracleValidationStatus.AGREE,
            "identity"
        );
        assertThrows(NullPointerException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                null, "2 * x", List.of(), true, oracle));
        assertThrows(NullPointerException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                "x + x", null, List.of(), true, oracle));
        assertThrows(IllegalArgumentException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                "  ", "2 * x", List.of(), true, oracle));
        assertThrows(IllegalArgumentException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                "x + x", "\t", List.of(), true, oracle));
        assertThrows(NullPointerException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                "x + x", "2 * x", null, true, oracle));
        assertThrows(NullPointerException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                "x + x", "2 * x", List.of(), true, null));
        assertThrows(NullPointerException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                "x + x", "2 * x", List.of((String) null), true, oracle));
        assertThrows(IllegalArgumentException.class, () ->
            TargetFreeIntrinsicCandidateValidator.validate(
                "x + x", "2 * x", List.of("  "), true, oracle));
    }

    private static final class FakeOracle implements OracleValidator {
        private final OracleValidationStatus status;
        private final String evidence;
        private boolean invoked;

        private FakeOracle(
            OracleValidationStatus status,
            String evidence
        ) {
            this.status = status;
            this.evidence = evidence;
        }

        @Override
        public OracleValidation validateEquivalence(
            String leftExpression,
            String rightExpression
        ) {
            invoked = true;
            return new OracleValidation(status, evidence);
        }

        boolean invoked() {
            return invoked;
        }
    }
}
