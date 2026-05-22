package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link ProofWorker} that tries a sequence of workers and returns the best
 * outcome.
 *
 * <p>The workers are tried in the order given at construction time.  The first
 * result with status {@link CandidateProofStatus#FORMALLY_PROVED} is returned
 * immediately (short-circuit).  If no worker achieves a full proof the result
 * with the highest {@link CandidateProofStatus} ordinal is returned instead.</p>
 *
 * <p>The composite {@link #workerId()} is derived from the member worker ids,
 * e.g. {@code "composite(lean4,smtlib2)"}.  This means the cache key changes
 * whenever the set of members changes, which is the desired behaviour: a cache
 * entry for a different worker composition cannot be reused.</p>
 */
public final class CompositeProofWorker implements ProofWorker {

    private final List<ProofWorker> workers;
    private final String workerId;

    public CompositeProofWorker(List<ProofWorker> workers) {
        if (workers == null || workers.isEmpty()) {
            throw new IllegalArgumentException("workers must not be null or empty");
        }
        this.workers = List.copyOf(workers);
        this.workerId = "composite(" + workers.stream()
            .map(ProofWorker::workerId)
            .collect(Collectors.joining(",")) + ")";
    }

    @Override
    public Result prove(RuleCandidate candidate, List<Assumption> assumptions) {
        Result best = null;
        for (ProofWorker worker : workers) {
            Result result = worker.prove(candidate, assumptions);
            if (result.status() == CandidateProofStatus.FORMALLY_PROVED) {
                return result;
            }
            if (best == null || result.status().ordinal() > best.status().ordinal()) {
                best = result;
            }
        }
        return best != null ? best
            : new Result(candidate, candidate.proofStatus(), "", "none", 0L);
    }

    @Override
    public String workerId() {
        return workerId;
    }

    /** Expose the individual workers for diagnostic purposes. */
    public List<ProofWorker> workers() {
        return workers;
    }
}
