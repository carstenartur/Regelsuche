package de.regelsuche.proof;

/**
 * Lifecycle states of a {@link ProofJob}.
 *
 * <p>State machine:
 * <pre>
 *   QUEUED → RUNNING → DONE
 *                   ↘ FAILED
 *                   ↘ RETRYING → RUNNING
 *   QUEUED → CANCELLED
 *   RUNNING → CANCELLED
 * </pre>
 * </p>
 */
public enum ProofJobStatus {
    /** Submitted but not yet dispatched to a worker. */
    QUEUED,
    /** Currently being executed by a worker. */
    RUNNING,
    /** A previous attempt failed; the job has been requeued. */
    RETRYING,
    /** Worker returned a successful proof result. */
    DONE,
    /** All retry attempts exhausted without success. */
    FAILED,
    /** Explicitly cancelled by the caller. */
    CANCELLED;

    /** @return {@code true} for terminal states that never transition again. */
    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
