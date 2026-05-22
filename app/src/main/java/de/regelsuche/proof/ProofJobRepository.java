package de.regelsuche.proof;

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for {@link ProofJob}s.
 *
 * <p>Implementations may be in-memory ({@link InMemoryProofJobRepository}) or
 * backed by a database (Neo4j, SQLite, …).  All mutating methods operate on
 * the <em>snapshot</em> semantics of {@link ProofJob}: the full record is
 * replaced atomically.</p>
 */
public interface ProofJobRepository {

    /** Insert a new job or replace an existing one with the same {@link ProofJob#id()}. */
    void save(ProofJob job);

    Optional<ProofJob> findById(String id);

    List<ProofJob> findAll();

    List<ProofJob> findByStatus(ProofJobStatus status);

    /**
     * Returns the next job to execute: the highest-priority (lowest numeric
     * {@link ProofJob#priority()}) job in {@link ProofJobStatus#QUEUED} or
     * {@link ProofJobStatus#RETRYING} state, oldest first when priorities tie.
     */
    Optional<ProofJob> findNextQueued();

    /** Remove a job by id (no-op if not found). */
    void delete(String id);
}
