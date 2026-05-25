package de.regelsuche.mining;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory implementation of {@link HypothesisRepository}.
 *
 * <p>Thread-safety: not guaranteed — use external synchronisation when
 * multiple threads access the same instance (e.g. during parallel discovery
 * workers).  For single-threaded discovery pipelines this is sufficient.</p>
 */
public class InMemoryHypothesisRepository implements HypothesisRepository {

    private final Map<String, RuleCandidate> store = new LinkedHashMap<>();

    @Override
    public void save(String hypothesisId, RuleCandidate hypothesis) {
        if (hypothesisId == null || hypothesisId.isBlank()) {
            throw new IllegalArgumentException("hypothesisId must not be blank");
        }
        store.put(hypothesisId, hypothesis);
    }

    @Override
    public Optional<RuleCandidate> findById(String hypothesisId) {
        return Optional.ofNullable(store.get(hypothesisId));
    }

    @Override
    public List<RuleCandidate> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void delete(String hypothesisId) {
        store.remove(hypothesisId);
    }
}
