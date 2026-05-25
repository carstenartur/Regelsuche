package de.regelsuche.checkpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe in-memory implementation of {@link SearchCheckpointRepository}. */
public class InMemorySearchCheckpointRepository implements SearchCheckpointRepository {
    private final ConcurrentMap<String, SearchCheckpoint> store = new ConcurrentHashMap<>();

    @Override
    public void save(SearchCheckpoint checkpoint) {
        store.put(checkpoint.jobId(), checkpoint);
    }

    @Override
    public Optional<SearchCheckpoint> findByJobId(String jobId) {
        return Optional.ofNullable(store.get(jobId));
    }

    @Override
    public List<SearchCheckpoint> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public boolean delete(String jobId) {
        return store.remove(jobId) != null;
    }
}
