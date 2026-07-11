package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProofConfirmationTest {

    @Test
    void isConfirmedReturnsTrueOnlyForProverConfirmed() {
        ProofConfirmation confirmed = new ProofConfirmation(
            "lean4", "4.x", "lean ./proof.lean", 30_000L,
            "PROVER_CONFIRMED", "abc123", "fp", "rev1", Instant.now());
        assertTrue(confirmed.isConfirmed());

        ProofConfirmation failed = new ProofConfirmation(
            "lean4", "4.x", "lean ./proof.lean", 30_000L,
            "PROVER_FAILED", "abc123", "fp", "rev1", Instant.now());
        assertFalse(failed.isConfirmed());

        ProofConfirmation scriptOnly = new ProofConfirmation(
            "lean4", "4.x", "", 30_000L,
            "SCRIPT_GENERATED", "abc123", "fp", "rev1", Instant.now());
        assertFalse(scriptOnly.isConfirmed());
    }

    @Test
    void isValidForReturnsTrueWhenBothHashesMatch() {
        ProofConfirmation confirmation = new ProofConfirmation(
            "lean4", "4.x", "lean ./proof.lean", 30_000L,
            "PROVER_CONFIRMED", "abc123", "fp-sorted", "cand-rev-1", Instant.now());

        assertTrue(confirmation.isValidFor("cand-rev-1", "fp-sorted"));
        assertFalse(confirmation.isValidFor("cand-rev-2", "fp-sorted"),
            "changed candidate revision must invalidate");
        assertFalse(confirmation.isValidFor("cand-rev-1", "fp-changed"),
            "changed assumptions must invalidate");
        assertFalse(confirmation.isValidFor(null, "fp-sorted"));
        assertFalse(confirmation.isValidFor("cand-rev-1", null));
    }

    @Test
    void containerReproductionCommandContainsProverAndTimeout() {
        ProofConfirmation confirmation = new ProofConfirmation(
            "lean4", "4.3.0", "lean ./proof.lean", 60_000L,
            "PROVER_CONFIRMED", "hash123.lean", "fp", "rev1", Instant.now());

        String cmd = confirmation.containerReproductionCommand("/proofs");

        assertTrue(cmd.contains("lean4"), "should contain prover name");
        assertTrue(cmd.contains("60"), "should contain timeout in seconds");
        assertTrue(cmd.contains("/proofs"), "should contain mount path");
        assertTrue(cmd.contains("docker run"), "should be a docker run command");
    }

    @Test
    void ofFactoryBindsExecutionResultDetails() {
        ProverExecutionResult result = new ProverExecutionResult(
            ProverExecutionResult.Status.PROVER_CONFIRMED, 0, "ok", "", 1000L, "lean4");

        ProofConfirmation confirmation = ProofConfirmation.of(
            result, "artifact-hash", "fp-sorted", "cand-rev", "lean ./proof.lean", 30_000L);

        assertEquals("lean4", confirmation.proverName());
        assertEquals("", confirmation.proverVersion(),
            "of() should leave proverVersion blank when ProverExecutionResult has no version field");
        assertEquals("PROVER_CONFIRMED", confirmation.exitState());
        assertEquals("artifact-hash", confirmation.artifactHash());
        assertEquals("fp-sorted", confirmation.assumptionsFingerprint());
        assertEquals("cand-rev", confirmation.candidateRevisionHash());
        assertTrue(confirmation.isConfirmed());
    }

    @Test
    void nullsAreHandledGracefully() {
        ProofConfirmation confirmation = new ProofConfirmation(
            "lean4", null, null, 0L,
            "SCRIPT_GENERATED", null, null, null, null);

        assertEquals("", confirmation.proverVersion());
        assertEquals("", confirmation.invocationCommand());
        assertEquals("", confirmation.artifactHash());
        assertEquals("", confirmation.assumptionsFingerprint());
        assertEquals("", confirmation.candidateRevisionHash());
        assertFalse(confirmation.isValidFor(null, null));
    }
}
