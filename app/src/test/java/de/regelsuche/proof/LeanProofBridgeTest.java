package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeanProofBridgeTest {
    @Test
    void emitsSkeletonAndMarksFormallyProvable() {
        LeanProofBridge bridge = new LeanProofBridge();
        ProofBridge.ProofAttempt attempt = bridge.prove(
            "a / b * b",
            "a",
            List.of(Assumption.nonZero("b"))
        );
        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE, attempt.status());
        assertEquals("lean4", attempt.tool());
        assertTrue(attempt.artifact().contains("theorem regelsuche_lemma"));
        assertTrue(attempt.artifact().contains("≠ 0"));
        assertTrue(attempt.artifact().contains("sorry"));
    }
}
