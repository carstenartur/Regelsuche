package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test pinning the proof-bridge contract:
 * scripts are <em>always</em> generated and exported, but the candidate is
 * lifted to {@link CandidateProofStatus#FORMALLY_PROVED} only when a
 * {@link ProverExecutor} actually returned
 * {@link ProverExecutionResult.Status#PROVER_CONFIRMED}.
 */
class ProofBridgeFormallyProvedTest {

    private RuleCandidate baseline() {
        return new RuleCandidate(
            "a + b",
            "b + a",
            5,
            1.0,
            2,
            true,
            true,
            false,
            List.of(),
            RuleStatus.NEW,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            "hash",
            List.of()
        );
    }

    private ProofBridge stubBridge() {
        return (left, right, assumptions) -> new ProofBridge.ProofAttempt(
            CandidateProofStatus.FORMALLY_PROVABLE,
            "theorem rewrite_proof : " + left + " = " + right + " := by sorry\n",
            "lean4"
        );
    }

    @Test
    void proofBridgeOnlyMarksProvedAfterSuccessfulExecution(@TempDir Path tempDir) throws IOException {
        // 1. Without executor: bridge generates the script and exports the
        //    artifact, but does NOT mark the candidate as formally proved.
        ProofBridgeService noExecutor = new ProofBridgeService(stubBridge(), tempDir);
        ProofBridgeService.ProofAttemptOutcome generated = noExecutor.attemptWithDetails(
            baseline(), List.of(Assumption.nonZero("x")));
        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE, generated.candidate().proofStatus(),
            "without execution the candidate must not be marked FORMALLY_PROVED");
        assertNotNull(generated.artifactPath(), "the script artifact must be exported");
        try (var stream = Files.list(tempDir)) {
            assertTrue(stream.findAny().isPresent(),
                "artifact directory must contain the generated proof script");
        }

        // 2. With a failing executor: stdout/stderr/exit are still recorded
        //    but the candidate stays below FORMALLY_PROVED.
        ProverExecutor failing = new ProverExecutor(
            List.of("false"),
            "lean4",
            ".lean",
            Duration.ofSeconds(5),
            (exit, out, err) -> exit == 0
        );
        ProofBridgeService failingService = new ProofBridgeService(stubBridge(), null, failing);
        ProofBridgeService.ProofAttemptOutcome failed = failingService.attemptWithDetails(
            baseline(), List.of());
        assertEquals(ProverExecutionResult.Status.PROVER_FAILED, failed.execution().status());
        assertNotNull(failed.execution().stdout(), "stdout must be captured");
        assertNotNull(failed.execution().stderr(), "stderr must be captured");
        assertTrue(failed.execution().exitCode() != 0, "exit code must be recorded");
        assertTrue(failed.candidate().proofStatus().ordinal()
            < CandidateProofStatus.FORMALLY_PROVED.ordinal(),
            "failed proofs must not promote to FORMALLY_PROVED");

        // 3. With a successful executor: candidate is finally lifted.
        ProverExecutor success = new ProverExecutor(
            List.of("true"),
            "lean4",
            ".lean",
            Duration.ofSeconds(5),
            (exit, out, err) -> exit == 0
        );
        ProofBridgeService confirmingService = new ProofBridgeService(stubBridge(), null, success);
        ProofBridgeService.ProofAttemptOutcome confirmed = confirmingService.attemptWithDetails(
            baseline(), List.of());
        assertEquals(ProverExecutionResult.Status.PROVER_CONFIRMED, confirmed.execution().status());
        assertEquals(CandidateProofStatus.FORMALLY_PROVED, confirmed.candidate().proofStatus(),
            "successful execution must lift candidate to FORMALLY_PROVED");
    }
}
