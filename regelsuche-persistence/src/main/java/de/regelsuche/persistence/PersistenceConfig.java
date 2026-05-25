package de.regelsuche.persistence;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolved persistence configuration.
 *
 * <p>The killer-demo standard mode is engineered around two simple knobs:</p>
 *
 * <ul>
 *   <li>{@code REGELSUCHE_PERSISTENCE_MODE} (or JVM property
 *       {@code regelsuche.persistence.mode}) — see {@link GraphPersistenceMode}.</li>
 *   <li>{@code REGELSUCHE_PERSISTENCE_PATH} (or JVM property
 *       {@code regelsuche.persistence.path}) — directory for {@link
 *       GraphPersistenceMode#JSON_FILE} / {@link GraphPersistenceMode#EMBEDDED_NEO4J}.
 *       Defaults to {@code ./data/regelsuche}.</li>
 * </ul>
 *
 * <p>The optional Full Mode reads the standard PostgreSQL and Neo4j environment
 * variables: {@code POSTGRES_URL}, {@code POSTGRES_USER}, {@code
 * POSTGRES_PASSWORD}, {@code NEO4J_URI}, {@code NEO4J_USER}, {@code
 * NEO4J_PASSWORD}. If the PostgreSQL triplet is set <em>and</em> the mode
 * hasn't been chosen explicitly, the configuration resolves to {@link
 * GraphPersistenceMode#POSTGRESQL_WITH_JSON_FALLBACK}; if only the Neo4j
 * triplet is present it resolves to {@link GraphPersistenceMode#REMOTE_NEO4J}.</p>
 *
 * <p>This class is intentionally a plain immutable record + a static factory
 * so it can be exercised from tests without touching real environment
 * variables.</p>
 */
public record PersistenceConfig(
    GraphPersistenceMode mode,
    Path storagePath,
    String neo4jUri,
    String neo4jUser,
    String neo4jPassword,
    String postgresUrl,
    String postgresUser,
    String postgresPassword
) {

    public static final String ENV_MODE = "REGELSUCHE_PERSISTENCE_MODE";
    public static final String ENV_PATH = "REGELSUCHE_PERSISTENCE_PATH";
    public static final String ENV_NEO4J_URI = "NEO4J_URI";
    public static final String ENV_NEO4J_USER = "NEO4J_USER";
    public static final String ENV_NEO4J_PASSWORD = "NEO4J_PASSWORD";
    public static final String ENV_POSTGRES_URL = "POSTGRES_URL";
    public static final String ENV_POSTGRES_USER = "POSTGRES_USER";
    public static final String ENV_POSTGRES_PASSWORD = "POSTGRES_PASSWORD";
    public static final String PROP_MODE = "regelsuche.persistence.mode";
    public static final String PROP_PATH = "regelsuche.persistence.path";
    public static final String PROP_POSTGRES_URL = "regelsuche.postgres.url";
    public static final String PROP_POSTGRES_USER = "regelsuche.postgres.user";
    public static final String PROP_POSTGRES_PASSWORD = "regelsuche.postgres.password";
    public static final String DEFAULT_PATH = "./data/regelsuche";

    public PersistenceConfig(
        GraphPersistenceMode mode,
        Path storagePath,
        String neo4jUri,
        String neo4jUser,
        String neo4jPassword
    ) {
        this(mode, storagePath, neo4jUri, neo4jUser, neo4jPassword, null, null, null);
    }

    public PersistenceConfig {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (storagePath == null) {
            throw new IllegalArgumentException("storagePath must not be null");
        }
    }

    /** Ephemeral in-memory configuration with a placeholder storage path. */
    public static PersistenceConfig inMemory() {
        return new PersistenceConfig(
            GraphPersistenceMode.IN_MEMORY,
            Paths.get(DEFAULT_PATH),
            null, null, null, null, null, null
        );
    }

    /** File-backed configuration rooted at {@code path}. */
    public static PersistenceConfig jsonFile(Path path) {
        return new PersistenceConfig(
            GraphPersistenceMode.JSON_FILE,
            path,
            null, null, null, null, null, null
        );
    }

    /** PostgreSQL-backed Discovery metadata with JSON fallback for graph-shaped artifacts. */
    public static PersistenceConfig postgresqlWithJsonFallback(
        Path path,
        String postgresUrl,
        String postgresUser,
        String postgresPassword
    ) {
        return new PersistenceConfig(
            GraphPersistenceMode.POSTGRESQL_WITH_JSON_FALLBACK,
            path,
            null, null, null,
            postgresUrl, postgresUser, postgresPassword
        );
    }

    /**
     * Resolve a {@link PersistenceConfig} from environment variables and
     * JVM properties. Explicit JVM properties win over environment
     * variables; if neither is set, defaults to {@link
     * GraphPersistenceMode#IN_MEMORY} so unit tests and short-lived CLI
     * commands stay side-effect-free.
     *
     * <p>The Docker image sets {@code REGELSUCHE_PERSISTENCE_MODE=JSON_FILE}
     * and {@code REGELSUCHE_PERSISTENCE_PATH=/opt/regelsuche/data} via the
     * {@code Dockerfile} so the killer-demo's first run already produces
     * persistent artifacts.</p>
     */
    public static PersistenceConfig fromEnvironment(Map<String, String> env) {
        String rawMode = firstNonBlank(
            System.getProperty(PROP_MODE),
            env.get(ENV_MODE)
        );
        String rawPath = firstNonBlank(
            System.getProperty(PROP_PATH),
            env.get(ENV_PATH)
        );
        String uri = firstNonBlank(env.get(ENV_NEO4J_URI), System.getProperty("regelsuche.neo4j.uri"));
        String user = firstNonBlank(env.get(ENV_NEO4J_USER), System.getProperty("regelsuche.neo4j.user"));
        String password = firstNonBlank(env.get(ENV_NEO4J_PASSWORD), System.getProperty("regelsuche.neo4j.password"));
        String postgresUrl = firstNonBlank(env.get(ENV_POSTGRES_URL), System.getProperty(PROP_POSTGRES_URL));
        String postgresUser = firstNonBlank(env.get(ENV_POSTGRES_USER), System.getProperty(PROP_POSTGRES_USER));
        String postgresPassword = firstNonBlank(env.get(ENV_POSTGRES_PASSWORD), System.getProperty(PROP_POSTGRES_PASSWORD));

        GraphPersistenceMode mode;
        if (rawMode != null) {
            mode = parseMode(rawMode);
        } else if (postgresUrl != null && postgresUser != null && postgresPassword != null) {
            // Auto-detect relational Full Mode when the PostgreSQL env vars are all present.
            mode = GraphPersistenceMode.POSTGRESQL_WITH_JSON_FALLBACK;
        } else if (uri != null && user != null && password != null) {
            // Auto-detect Full Mode when the Neo4j env vars are all present.
            mode = GraphPersistenceMode.REMOTE_NEO4J;
        } else {
            mode = GraphPersistenceMode.IN_MEMORY;
        }

        Path path = Paths.get(rawPath == null ? DEFAULT_PATH : rawPath);
        return new PersistenceConfig(mode, path, uri, user, password, postgresUrl, postgresUser, postgresPassword);
    }

    /** Convenience: read from the JVM's real environment. */
    public static PersistenceConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    private static GraphPersistenceMode parseMode(String raw) {
        try {
            return GraphPersistenceMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "Unsupported persistence mode '" + raw + "'. Expected one of: "
                    + java.util.Arrays.toString(GraphPersistenceMode.values()),
                ex
            );
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** Convenience: whether all Neo4j credentials are set. */
    public boolean hasNeo4jCredentials() {
        return neo4jUri != null && neo4jUser != null && neo4jPassword != null;
    }

    /** Convenience: whether all PostgreSQL connection settings are set. */
    public boolean hasPostgresCredentials() {
        return postgresUrl != null && postgresUser != null && postgresPassword != null;
    }

    public Optional<String> neo4jUriOptional() {
        return Optional.ofNullable(neo4jUri);
    }
}
