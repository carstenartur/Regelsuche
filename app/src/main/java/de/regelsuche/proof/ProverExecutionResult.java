package de.regelsuche.proof;

import java.util.Objects;

/**
 * Outcome of attempting to execute a prover artifact (Lean lemma file,
 * SMT-LIB script, ...) against a real external tool.
 */
public record ProverExecutionResult(
    Status status,
    int exitCode,
    String stdout,
    String stderr,
    long durationMillis,
    String tool
) {
    public ProverExecutionResult {
        Objects.requireNonNull(status, "status");
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        tool = tool == null ? "unknown" : tool;
    }

    public enum Status {
        /**
         * No execution was attempted because no executor was configured;
         * only a script was produced.
         */
        SCRIPT_GENERATED,
        /** The configured prover executable could not be found on PATH. */
        PROVER_NOT_AVAILABLE,
        /** The prover did not finish within the configured timeout. */
        PROVER_TIMEOUT,
        /** The prover finished, but reported a non-success exit code. */
        PROVER_FAILED,
        /** The prover finished and signalled success. */
        PROVER_CONFIRMED
    }
}
