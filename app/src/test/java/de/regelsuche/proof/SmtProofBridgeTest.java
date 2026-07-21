package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmtProofBridgeTest {
    @Test
    void emitsSmtLibScriptForEquivalenceQuery() {
        SmtProofBridge bridge = new SmtProofBridge();
        ProofBridge.ProofAttempt attempt = bridge.prove(
            "(a + b) * (a + b)",
            "a * a + 2 * a * b + b * b",
            List.of()
        );
        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE, attempt.status());
        assertEquals("smtlib2", attempt.tool());
        String artifact = attempt.artifact();
        assertTrue(artifact.contains("(declare-const a Real)"));
        assertTrue(artifact.contains("(declare-const b Real)"));
        assertTrue(artifact.contains("(assert (not (="));
        assertTrue(artifact.contains("(check-sat)"));
    }

    @Test
    void expandsIntegralPowersForSophieGermainIdentity() {
        SmtProofBridge bridge = new SmtProofBridge();

        ProofBridge.ProofAttempt attempt = bridge.prove(
            "a^4 + 4*b^4",
            "(a^2 - 2*a*b + 2*b^2)*(a^2 + 2*a*b + 2*b^2)",
            List.of());

        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE, attempt.status());
        assertEquals("smtlib2", attempt.tool());
        String artifact = attempt.artifact();
        assertTrue(artifact.contains("(declare-const a Real)"));
        assertTrue(artifact.contains("(declare-const b Real)"));
        assertTrue(artifact.contains("(* (* (* a a) a) a)"));
        assertTrue(artifact.contains("(* b b)"));
        assertFalse(artifact.contains("(declare-fun pow"));
        assertFalse(artifact.contains("(pow "));
        assertTrue(artifact.contains("(check-sat)"));
    }

    @Test
    void declaresFallbackPowWithCorrectBinaryArity() {
        SmtProofBridge bridge = new SmtProofBridge();

        ProofBridge.ProofAttempt attempt = bridge.prove("a^n", "pow(a,n)", List.of());

        String artifact = attempt.artifact();
        assertTrue(artifact.contains("(declare-fun pow (Real Real) Real)"));
        assertTrue(artifact.contains("(pow a n)"));
        assertFalse(artifact.contains("(declare-fun pow (Real) Real)"));
    }

    @Test
    void emitsSmtObligationForRetainedAutonomousProductionCandidate() {
        SmtProofBridge bridge = new SmtProofBridge();

        ProofBridge.ProofAttempt attempt = bridge.prove(
            "(A + 2)*x + A*x",
            "(2*A + 2)*x",
            List.of());

        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE, attempt.status());
        assertEquals("smtlib2", attempt.tool());
        String artifact = attempt.artifact();
        assertTrue(artifact.contains("(declare-const A Real)"));
        assertTrue(artifact.contains("(declare-const x Real)"));
        assertTrue(artifact.contains("(assert (not (="));
        assertTrue(artifact.contains("(check-sat)"));
    }

    @Test
    void includesDistinctAssertionForNonZeroAssumptions() {
        SmtProofBridge bridge = new SmtProofBridge();
        ProofBridge.ProofAttempt attempt = bridge.prove(
            "a / b",
            "a / b",
            List.of(Assumption.nonZero("b"))
        );
        assertTrue(attempt.artifact().contains("(distinct b 0)"));
    }
}
