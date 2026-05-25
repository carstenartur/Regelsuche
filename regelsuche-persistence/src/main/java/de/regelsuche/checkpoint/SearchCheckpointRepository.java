package de.regelsuche.checkpoint;

import java.util.List;
import java.util.Optional;

/**
 * Storage for {@link SearchCheckpoint}s keyed by job id. Implementations
 * may persist checkpoints to memory ({@link InMemorySearchCheckpointRepository}),
 * a file ({@link JsonFileSearchCheckpointRepository}) or any other backend
 * (Neo4j, ...).
 */
public interface SearchCheckpointRepository {
    void save(SearchCheckpoint checkpoint);

    Optional<SearchCheckpoint> findByJobId(String jobId);

    List<SearchCheckpoint> findAll();

    boolean delete(String jobId);
}
