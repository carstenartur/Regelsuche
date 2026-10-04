package de.regelsuche.proof;

import de.regelsuche.validation.CandidateProofStatus;
import java.util.Optional;

/**
 * Status cache for previous attempts. It is not independently replayable proof evidence.
 *
 * <p>The cache operates on {@link ProofCacheKey}s, which embed the prover
 * configuration; different configurations do not share cache scope. The scheduler
 * reuses non-proof results only. Formal confirmations require fresh execution.</p>
 */
public interface ProofCache {

    /** @return the cached status for a previous attempt; not authority to accept a formal proof. */
    Optional<CandidateProofStatus> get(ProofCacheKey key);

    /**
     * Store a result.  Implementations may choose to only cache positive
     * results (e.g. {@code status >= FORMALLY_PROVABLE}) and ignore
     * lower-quality hits.
     */
    void put(ProofCacheKey key, CandidateProofStatus status);

    /**
     * @return the full {@link ProofCacheEntry} if cached. Default delegates to
     *         {@link #get(ProofCacheKey)} so legacy in-memory implementations
     *         keep working with status-only semantics.
     */
    default Optional<ProofCacheEntry> getEntry(ProofCacheKey key) {
        return get(key).map(ProofCacheEntry::ofStatus);
    }

    /**
     * Store the full entry. Default implementation discards the extra metadata
     * by delegating to {@link #put(ProofCacheKey, CandidateProofStatus)}.
     */
    default void putEntry(ProofCacheKey key, ProofCacheEntry entry) {
        put(key, entry.status());
    }

    /** @return the current number of cache entries. */
    int size();

    /** Remove all cached entries. */
    void clear();
}
