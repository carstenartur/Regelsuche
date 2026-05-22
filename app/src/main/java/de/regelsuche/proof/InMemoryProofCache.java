package de.regelsuche.proof;

import de.regelsuche.mining.CandidateProofStatus;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory {@link ProofCache}.
 *
 * <p>Only caches results that reach at least
 * {@link CandidateProofStatus#FORMALLY_PROVABLE}: there is no point in
 * caching a skeleton failure that will just be retried in the next run.</p>
 */
public final class InMemoryProofCache implements ProofCache {

    private static final CandidateProofStatus MIN_CACHE_THRESHOLD =
        CandidateProofStatus.FORMALLY_PROVABLE;

    private final Map<ProofCacheKey, CandidateProofStatus> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<CandidateProofStatus> get(ProofCacheKey key) {
        return Optional.ofNullable(cache.get(key));
    }

    @Override
    public void put(ProofCacheKey key, CandidateProofStatus status) {
        if (status != null && status.ordinal() >= MIN_CACHE_THRESHOLD.ordinal()) {
            cache.put(key, status);
        }
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Override
    public void clear() {
        cache.clear();
    }
}
