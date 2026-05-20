package de.regelsuche.api.searchgraph;

import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for {@link SearchGraphRecord} (search graph,
 * replays, macro rules, identity reports, generated exports, timestamps,
 * search profile and domain selection).
 *
 * <p>Three implementations live alongside this interface:
 * <ul>
 *   <li>{@link InMemorySearchGraphRepository} – default unit-test backend.</li>
 *   <li>{@link JsonFileSearchGraphRepository} – durable single-file JSON store.</li>
 *   <li>{@code Neo4jSearchGraphRepository} – Neo4j-backed graph store reusing
 *       the project's existing {@code neo4j-java-driver} dependency.</li>
 * </ul>
 */
public interface SearchGraphRepository {

    void save(SearchGraphRecord record);

    Optional<SearchGraphRecord> findById(String id);

    List<SearchGraphRecord> findAll();

    default void delete(String id) {
        throw new UnsupportedOperationException("delete not supported by " + getClass().getSimpleName());
    }

    default void close() {
        // default no-op for in-memory implementations
    }
}
