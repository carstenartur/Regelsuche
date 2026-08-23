package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExactPolynomialPatternVerificationServiceTest {
    private final ExactPolynomialPatternVerificationService service =
        new ExactPolynomialPatternVerificationService();

    @Test
    void provesGeneralizedPatternsEmittedByTheMiningLayer() {
        ExactPolynomialPatternIdentityVerifier.Verification verification =
            service.verify("(A + 0) * 1", "A");

        assertTrue(verification.proved());
        assertEquals(
            ExactPolynomialPatternIdentityVerifier.Status.PROVED,
            verification.status());
        assertTrue(verification.proofHash().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void keepsUnsupportedConditionalFragmentsClosed() {
        ExactPolynomialPatternIdentityVerifier.Verification verification =
            service.verify("A / B", "A");

        assertEquals(
            ExactPolynomialPatternIdentityVerifier.Status.UNSUPPORTED,
            verification.status());
    }

    @Test
    void rejectsBlankPatternInputBeforeParsing() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.verify(" ", "A"));
    }
}
