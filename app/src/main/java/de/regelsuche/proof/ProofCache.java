package de.regelsuche.proof;

import de.regelsuche.mining.CandidateProofStatus;
import java.util.Optional;

/**
 * Cache that avoids re-running the same proof obligation.
 *
 * <p>The cache operates on {@link ProofCacheKey}s, which embed the prover
 * version; different prover versions produce different keys and never share
 * cached results.</p>
 */
public interface ProofCache {

    /** @return the cached status if a previous proof attempt succeeded. */
    Optional<CandidateProofStatus> get(ProofCacheKey key);

    /**
     * Store a result.  Implementations may choose to only cache positive
     * results (e.g. {@code status >= FORMALLY_PROVABLE}) and ignore
     * lower-quality hits.
     */
    void put(ProofCacheKey key, CandidateProofStatus status);

    /** @return the current number of cache entries. */
    int size();

    /** Remove all cached entries. */
    void clear();
}
