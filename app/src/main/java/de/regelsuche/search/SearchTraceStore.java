package de.regelsuche.search;

import java.util.List;
import java.util.Optional;

/**
 * Stable port for persisting and retrieving search traces.
 *
 * <p>Introduced as part of Teil 0 of the Discovery Epic (issue #41,
 * "Interfaces zuerst"): replay, reporting and experiment-runner features
 * must depend on this abstraction so they can be wired against any
 * concrete backend (in-memory, JSON file, PostgreSQL, Neo4j, …) without
 * leaking infrastructure into the mathematical core.
 *
 * <p>The trace payload is intentionally typed as a free-form record so the
 * port stays infrastructure-agnostic. Concrete trace shapes will be
 * introduced together with the search-trace feature and are not required
 * for Teil 0.
 */
public interface SearchTraceStore {

    /** Persist a search trace and return the assigned id. */
    String store(SearchTraceRecord trace);

    /** @return a previously stored trace, if known. */
    Optional<SearchTraceRecord> findById(String traceId);

    /** @return all stored traces (typically used for reports/dashboards). */
    List<SearchTraceRecord> findAll();

    /**
     * Minimal envelope for a stored search trace.
     *
     * @param id stable identifier assigned by the store
     * @param description human-readable label
     * @param payload serialised trace body (JSON, NDJSON, …) — opaque to
     *     the store
     */
    record SearchTraceRecord(String id, String description, String payload) {
    }
}
