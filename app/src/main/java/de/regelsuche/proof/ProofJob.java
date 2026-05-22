package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Persistent snapshot of a single proof request, managed by
 * {@link ProofJobRepository} and executed by {@link ProofJobScheduler}.
 *
 * <p>Jobs are value objects — every state transition creates a new instance via
 * one of the {@code with*()} helpers.  The {@link #priority()} field is
 * intentionally an {@code int}: lower values mean higher priority (0 = most
 * urgent), matching the convention used by
 * {@link java.util.concurrent.PriorityQueue}.</p>
 */
public record ProofJob(
    String id,
    String leftPattern,
    String rightPattern,
    List<Assumption> assumptions,
    ProofJobStatus status,
    int priority,
    int retryCount,
    int maxRetries,
    String workerType,
    Instant createdAt,
    Instant updatedAt,
    CandidateProofStatus resultStatus,
    String errorMessage
) {
    public ProofJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(leftPattern, "leftPattern");
        Objects.requireNonNull(rightPattern, "rightPattern");
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        status = status == null ? ProofJobStatus.QUEUED : status;
        if (priority < 0) {
            throw new IllegalArgumentException("priority must be >= 0");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        workerType = workerType == null ? "lean4" : workerType;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    /** Convenience constructor with defaults for optional fields. */
    public ProofJob(String id, String leftPattern, String rightPattern,
                    List<Assumption> assumptions, int priority, String workerType) {
        this(id, leftPattern, rightPattern, assumptions,
            ProofJobStatus.QUEUED, priority, 0, 3, workerType,
            null, null, null, null);
    }

    public ProofJob withStatus(ProofJobStatus newStatus) {
        return new ProofJob(id, leftPattern, rightPattern, assumptions,
            newStatus, priority, retryCount, maxRetries, workerType,
            createdAt, Instant.now(), resultStatus, errorMessage);
    }

    public ProofJob withRetry() {
        return new ProofJob(id, leftPattern, rightPattern, assumptions,
            ProofJobStatus.RETRYING, priority, retryCount + 1, maxRetries, workerType,
            createdAt, Instant.now(), resultStatus, errorMessage);
    }

    public ProofJob withDone(CandidateProofStatus result) {
        return new ProofJob(id, leftPattern, rightPattern, assumptions,
            ProofJobStatus.DONE, priority, retryCount, maxRetries, workerType,
            createdAt, Instant.now(), result, "");
    }

    public ProofJob withFailed(String reason) {
        return new ProofJob(id, leftPattern, rightPattern, assumptions,
            ProofJobStatus.FAILED, priority, retryCount, maxRetries, workerType,
            createdAt, Instant.now(), resultStatus, reason);
    }

    /** @return {@code true} if this job may still be retried. */
    public boolean canRetry() {
        return retryCount < maxRetries;
    }
}
