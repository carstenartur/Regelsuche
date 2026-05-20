package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProverExecutorAndBridgeTest {

    private RuleCandidate sampleCandidate() {
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
            RuleStatus.MATCHES_KNOWN_RULE,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            "hash",
            List.of()
        );
    }

    private ProofBridge stubBridge(String tool) {
        return (left, right, assumptions) -> new ProofBridge.ProofAttempt(
            CandidateProofStatus.FORMALLY_PROVABLE,
            "theorem rewrite_proof : " + left + " = " + right + " := by sorry\n",
            tool
        );
    }

    @Test
    void proofBridgeRunsLeanWhenAvailable() {
        // We invoke a guaranteed-success tool ("true") with a custom success
        // predicate. This simulates a prover whose binary is present and
        // returns success — the candidate should be lifted to FORMALLY_PROVED.
        ProverExecutor executor = new ProverExecutor(
            List.of("true"),
            "lean4",
            ".lean",
            Duration.ofSeconds(5),
            (exit, out, err) -> exit == 0
        );
        ProofBridgeService service = new ProofBridgeService(stubBridge("lean4"), null, executor);
        ProofBridgeService.ProofAttemptOutcome outcome = service.attemptWithDetails(
            sampleCandidate(),
            List.of(Assumption.nonZero("x"))
        );
        assertNotNull(outcome.execution());
        assertEquals(ProverExecutionResult.Status.PROVER_CONFIRMED, outcome.execution().status());
        assertEquals(CandidateProofStatus.FORMALLY_PROVED, outcome.candidate().proofStatus());
    }

    @Test
    void proofBridgeDoesNotMarkProvedWithoutExecution() {
        // Without an executor the bridge stays at the script's reported
        // status (FORMALLY_PROVABLE) — it never lifts to FORMALLY_PROVED.
        ProofBridgeService service = new ProofBridgeService(stubBridge("lean4"));
        RuleCandidate result = service.attempt(sampleCandidate(), List.of());
        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE, result.proofStatus());
    }

    @Test
    void proverNotAvailableYieldsExplicitStatus() {
        ProverExecutor executor = new ProverExecutor(
            List.of("regelsuche-nonexistent-binary-" + System.nanoTime()),
            "lean4",
            ".lean"
        );
        ProverExecutionResult result = executor.execute("dummy artifact");
        assertEquals(ProverExecutionResult.Status.PROVER_NOT_AVAILABLE, result.status());
    }

    @Test
    void proverFailedExitCodeDoesNotPromote() {
        ProverExecutor executor = new ProverExecutor(
            List.of("false"),
            "lean4",
            ".lean",
            Duration.ofSeconds(5),
            (exit, out, err) -> exit == 0
        );
        ProofBridgeService service = new ProofBridgeService(stubBridge("lean4"), null, executor);
        ProofBridgeService.ProofAttemptOutcome outcome = service.attemptWithDetails(
            sampleCandidate(),
            List.of()
        );
        assertEquals(ProverExecutionResult.Status.PROVER_FAILED, outcome.execution().status());
        assertTrue(outcome.candidate().proofStatus().ordinal()
            <= CandidateProofStatus.FORMALLY_PROVABLE.ordinal());
    }

    @Test
    void proverTimeoutYieldsTimeoutStatus() {
        // Use 'sh -c "sleep 5"' so the appended script path becomes $0 and
        // does not break the sleep invocation.
        ProverExecutor executor = new ProverExecutor(
            List.of("sh", "-c", "sleep 5"),
            "lean4",
            ".lean",
            Duration.ofMillis(200),
            (exit, out, err) -> exit == 0
        );
        ProverExecutionResult result = executor.execute("dummy");
        assertEquals(ProverExecutionResult.Status.PROVER_TIMEOUT, result.status());
    }

    @Test
    void allStatusValuesArePresent() {
        // Smoke test: future code paths can switch on these values.
        assertSame(ProverExecutionResult.Status.SCRIPT_GENERATED, ProverExecutionResult.Status.valueOf("SCRIPT_GENERATED"));
        assertSame(ProverExecutionResult.Status.PROVER_NOT_AVAILABLE, ProverExecutionResult.Status.valueOf("PROVER_NOT_AVAILABLE"));
        assertSame(ProverExecutionResult.Status.PROVER_TIMEOUT, ProverExecutionResult.Status.valueOf("PROVER_TIMEOUT"));
        assertSame(ProverExecutionResult.Status.PROVER_FAILED, ProverExecutionResult.Status.valueOf("PROVER_FAILED"));
        assertSame(ProverExecutionResult.Status.PROVER_CONFIRMED, ProverExecutionResult.Status.valueOf("PROVER_CONFIRMED"));
    }
}
