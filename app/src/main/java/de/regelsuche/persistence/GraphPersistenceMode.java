package de.regelsuche.persistence;

/**
 * Persistence backend selector for the killer-demo workbench.
 *
 * <p>The killer-demo's standard mode (single Docker image, one
 * {@code docker run}) must run without external infrastructure. The
 * {@link #IN_MEMORY} and {@link #JSON_FILE} backends satisfy that
 * requirement; {@link #REMOTE_NEO4J} is reserved for the optional Full Mode
 * shipped via {@code docker compose}.</p>
 *
 * <p>{@link #EMBEDDED_NEO4J} is declared so the configuration surface stays
 * stable, but the current distribution does not bundle the embedded Neo4j
 * server (it would require pulling in the GPL-licensed {@code neo4j}
 * artifact in addition to the Apache-licensed Java driver). When
 * {@code EMBEDDED_NEO4J} is requested today, {@link PersistenceContext}
 * transparently falls back to {@link #JSON_FILE} at the configured path and
 * emits a clear log line — the Docker image stays a single, self-contained
 * artifact and the demo flow keeps working.</p>
 */
public enum GraphPersistenceMode {
    /** Everything lives in JVM memory — fastest, no disk I/O, lost on exit. */
    IN_MEMORY,

    /**
     * File-backed local persistence (no external service required). The
     * killer-demo's Docker image defaults to this mode.
     */
    JSON_FILE,

    /**
     * Embedded Neo4j (in-process, single user). Not bundled with the current
     * distribution; routed to {@link #JSON_FILE} as a documented fallback.
     */
    EMBEDDED_NEO4J,

    /**
     * Remote Neo4j server reached via {@code bolt://}. Used by the optional
     * Full Mode (see {@code docker-compose.yml}).
     */
    REMOTE_NEO4J
}
