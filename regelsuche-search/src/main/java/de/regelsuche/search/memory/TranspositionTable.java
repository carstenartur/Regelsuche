package de.regelsuche.search.memory;

import java.util.Collection;
import java.util.Optional;

/**
 * "Mathematical transposition table" – a chess-engine-style cache of
 * canonical states the search has already encountered.
 *
 * <p>Backed in the killer-demo by one of three implementations selected via
 * {@link de.regelsuche.persistence.GraphPersistenceMode}: in-memory (default),
 * JSON file (persistent local mode) or remote Neo4j (Full Mode). The hashing
 * source-of-truth is {@link de.regelsuche.canonical.ExpressionCanonicalizer}.
 * </p>
 *
 * <p>{@link InMemoryTranspositionTable} is the reference implementation and
 * is also used in unit tests. All implementations must be thread-safe with
 * respect to concurrent {@code lookup} / {@code record} calls.</p>
 */
public interface TranspositionTable {

    /** Look up the entry for {@code canonicalHash}, if any. */
    Optional<TranspositionEntry> lookup(String canonicalHash);

    /**
     * Record {@code entry}. If an entry with the same hash already exists,
     * it is merged with the existing one via {@link TranspositionEntry#merge}.
     * Returns the resulting (possibly merged) entry.
     */
    TranspositionEntry record(TranspositionEntry entry);

    /** Returns a snapshot of all currently known entries. */
    Collection<TranspositionEntry> entries();

    /** Number of distinct canonical states known. */
    int size();

    /** Drop everything. Used in tests and for the {@code /api/memory/reset} call. */
    void clear();
}
