package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
