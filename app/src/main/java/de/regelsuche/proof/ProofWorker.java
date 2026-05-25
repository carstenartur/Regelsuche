package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Async-capable facade over a proof strategy (Lean, SMT, composite, ...).
 *
 * <p>Unlike the lower-level {@link ProofBridge} / {@link ProofBridgeService}
 * pair—which only handle a single synchronous artifact-generation + execution
 * step—{@code ProofWorker} is the unit that the {@link ProofJobScheduler}
 * dispatches to.  It carries a stable {@link #workerId()} that becomes part
 * of the {@link ProofCacheKey}, ensuring cached results are not reused across
 * incompatible prover versions.</p>
 */
public interface ProofWorker {

    /**
     * Synchronously attempt to prove {@code candidate} under {@code assumptions}.
     *
     * <p>Implementations must not throw; errors are captured in the returned
     * {@link Result} ({@link CandidateProofStatus#FORMALLY_PROVABLE} when the
     * prover is unavailable, {@link CandidateProofStatus#OBSERVED} on hard
     * failure).</p>
     */
    Result prove(RuleCandidate candidate, List<Assumption> assumptions);

    /**
     * Asynchronous variant — default wraps {@link #prove} in a
     * {@link CompletableFuture} executed on the common fork-join pool.
     */
    default CompletableFuture<Result> proveAsync(RuleCandidate candidate, List<Assumption> assumptions) {
        return CompletableFuture.supplyAsync(() -> prove(candidate, assumptions));
    }

    /**
     * Stable identifier used for cache-key versioning and job tracking
     * (e.g. {@code "lean4"}, {@code "smtlib2"}, {@code "composite(lean4,smtlib2)"}).
     */
    String workerId();

    /** Full outcome of a single proof attempt. */
    record Result(
        RuleCandidate updatedCandidate,
        CandidateProofStatus status,
        String artifact,
        String tool,
        long durationMillis
    ) {
        public Result {
            if (updatedCandidate == null) {
                throw new IllegalArgumentException("updatedCandidate must not be null");
            }
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
            artifact = artifact == null ? "" : artifact;
            tool = tool == null ? "unknown" : tool;
        }
    }
}
