package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.validation.CandidateProofStatus;
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
    String errorMessage,
    Integer attemptsStarted,
    Long attemptTimeoutMillis,
    Instant cancellationRequestedAt,
    String stopReason
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
        stopReason = stopReason == null ? "" : stopReason;
        if ((attemptsStarted != null && attemptsStarted < 0)
                || (attemptTimeoutMillis != null && attemptTimeoutMillis <= 0)) {
            throw new IllegalArgumentException("invalid proof attempt accounting");
        }
    }

    /** Snapshots predating attempt accounting retain unknown values. */
    public ProofJob(String id, String leftPattern, String rightPattern, List<Assumption> assumptions,
                    ProofJobStatus status, int priority, int retryCount, int maxRetries, String workerType,
                    Instant createdAt, Instant updatedAt, CandidateProofStatus resultStatus, String errorMessage) {
        this(id, leftPattern, rightPattern, assumptions, status, priority, retryCount, maxRetries, workerType,
            createdAt, updatedAt, resultStatus, errorMessage, null, null, null, "");
    }

    /** Convenience constructor with defaults for optional fields. */
    public ProofJob(String id, String leftPattern, String rightPattern,
                    List<Assumption> assumptions, int priority, String workerType) {
        this(id, leftPattern, rightPattern, assumptions,
            ProofJobStatus.QUEUED, priority, 0, 3, workerType,
            null, null, null, null);
    }

    ProofJob withBudget(long timeoutMillis) {
        return new ProofJob(id, leftPattern, rightPattern, assumptions, status, priority, retryCount,
            maxRetries, workerType, createdAt, updatedAt, resultStatus, errorMessage,
            0, timeoutMillis, null, "");
    }

    ProofJob startAttempt(long timeoutMillis) {
        Integer count = attemptsStarted == null ? null : Integer.valueOf(Math.incrementExact(attemptsStarted));
        return new ProofJob(id, leftPattern, rightPattern, assumptions, ProofJobStatus.RUNNING, priority,
            retryCount, maxRetries, workerType, createdAt, Instant.now(), resultStatus, errorMessage,
            count, timeoutMillis, cancellationRequestedAt, stopReason);
    }

    public ProofJob withStatus(ProofJobStatus status) {
        return transition(status, retryCount, resultStatus, errorMessage, cancellationRequestedAt, stopReason);
    }

    ProofJob stopping(String reason) {
        return transition(ProofJobStatus.CANCELLING, retryCount, null, errorMessage, Instant.now(), reason);
    }

    ProofJob stopped(String reason) {
        Instant requested = cancellationRequestedAt == null ? Instant.now() : cancellationRequestedAt;
        return transition(ProofJobStatus.CANCELLED, retryCount, null, errorMessage, requested, reason);
    }

    public ProofJob withRetry() {
        return transition(ProofJobStatus.RETRYING, retryCount + 1, resultStatus, errorMessage, null, "");
    }

    public ProofJob withDone(CandidateProofStatus result) {
        return transition(ProofJobStatus.DONE, retryCount, result, "", null, "");
    }

    public ProofJob withFailed(String reason) {
        return transition(ProofJobStatus.FAILED, retryCount, resultStatus, reason, null, "");
    }

    private ProofJob transition(ProofJobStatus next, int retries, CandidateProofStatus result,
                                String error, Instant requestTime, String reason) {
        return new ProofJob(id, leftPattern, rightPattern, assumptions, next, priority, retries, maxRetries,
            workerType, createdAt, Instant.now(), result, error, attemptsStarted, attemptTimeoutMillis,
            requestTime, reason);
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }
}
