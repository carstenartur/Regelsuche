package de.regelsuche.persistence;

import de.regelsuche.app.persistence.neo4j.Neo4jTranspositionTable;
import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.graph.Neo4jExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.Neo4jRuleInventoryRepository;
import de.regelsuche.inventory.RuleInventoryRepository;
import de.regelsuche.persistence.relational.PersistenceAdapterFactory;
import de.regelsuche.persistence.relational.RelationalPersistenceAdapters;
import de.regelsuche.search.memory.InMemoryTranspositionTable;
import de.regelsuche.search.memory.JsonFileTranspositionTable;
import de.regelsuche.search.memory.TranspositionTable;
import java.io.PrintStream;
import java.util.Optional;

/**
 * Resolved persistence wiring: an {@link ExpressionGraphStore} + a
 * {@link RuleInventoryRepository}, chosen based on a {@link PersistenceConfig}.
 *
 * <p>{@link #from(PersistenceConfig, PrintStream)} is the single entry point
 * the CLI / web layer uses; it never throws on missing Neo4j credentials,
 * instead logging the fallback decision so the killer-demo always boots.</p>
 */
public final class PersistenceContext implements AutoCloseable {

    private final GraphPersistenceMode effectiveMode;
    private final ExpressionGraphStore graphStore;
    private final RuleInventoryRepository inventoryRepository;
    private final TranspositionTable transpositionTable;
    private final RelationalPersistenceAdapters relationalAdapters;

    private PersistenceContext(
        GraphPersistenceMode effectiveMode,
        ExpressionGraphStore graphStore,
        RuleInventoryRepository inventoryRepository,
        TranspositionTable transpositionTable
    ) {
        this(effectiveMode, graphStore, inventoryRepository, transpositionTable, null);
    }

    private PersistenceContext(
        GraphPersistenceMode effectiveMode,
        ExpressionGraphStore graphStore,
        RuleInventoryRepository inventoryRepository,
        TranspositionTable transpositionTable,
        RelationalPersistenceAdapters relationalAdapters
    ) {
        this.effectiveMode = effectiveMode;
        this.graphStore = graphStore;
        this.inventoryRepository = inventoryRepository;
        this.transpositionTable = transpositionTable;
        this.relationalAdapters = relationalAdapters;
    }

    public GraphPersistenceMode effectiveMode() {
        return effectiveMode;
    }

    public ExpressionGraphStore graphStore() {
        return graphStore;
    }

    public RuleInventoryRepository inventoryRepository() {
        return inventoryRepository;
    }

    public TranspositionTable transpositionTable() {
        return transpositionTable;
    }

    public Optional<RelationalPersistenceAdapters> relationalAdapters() {
        return Optional.ofNullable(relationalAdapters);
    }

    /**
     * Build a {@link PersistenceContext} from {@code config}. Falls back to
     * a safe in-process mode and logs the reason if the requested mode is
     * unavailable (no Neo4j credentials, embedded Neo4j not bundled, ...).
     *
     * @param log destination for informational fallback messages; may be
     *            {@code null} to silence them
     */
    public static PersistenceContext from(PersistenceConfig config, PrintStream log) {
        switch (config.mode()) {
            case IN_MEMORY -> {
                return new PersistenceContext(
                    GraphPersistenceMode.IN_MEMORY,
                    new InMemoryExpressionGraphStore(),
                    new InMemoryRuleInventoryRepository(),
                    new InMemoryTranspositionTable()
                );
            }
            case JSON_FILE -> {
                JsonFileExpressionGraphStore graph = new JsonFileExpressionGraphStore(config.storagePath());
                JsonFileRuleInventoryRepository inventory = new JsonFileRuleInventoryRepository(config.storagePath());
                JsonFileTranspositionTable table = new JsonFileTranspositionTable(config.storagePath());
                if (log != null) {
                    log.println("Persistence: JSON_FILE at " + graph.filePath().toAbsolutePath());
                }
                return new PersistenceContext(GraphPersistenceMode.JSON_FILE, graph, inventory, table);
            }
            case EMBEDDED_NEO4J -> {
                // Embedded Neo4j is intentionally not bundled today (would
                // require pulling in the GPL-licensed `neo4j` artifact).
                // Fall back to JSON_FILE at the configured path so the
                // killer-demo's standard mode keeps working.
                JsonFileExpressionGraphStore graph = new JsonFileExpressionGraphStore(config.storagePath());
                JsonFileRuleInventoryRepository inventory = new JsonFileRuleInventoryRepository(config.storagePath());
                JsonFileTranspositionTable table = new JsonFileTranspositionTable(config.storagePath());
                if (log != null) {
                    log.println("Persistence: EMBEDDED_NEO4J requested but not bundled; "
                        + "using JSON_FILE at " + graph.filePath().toAbsolutePath());
                }
                return new PersistenceContext(GraphPersistenceMode.JSON_FILE, graph, inventory, table);
            }
            case REMOTE_NEO4J -> {
                if (!config.hasNeo4jCredentials()) {
                    if (log != null) {
                        log.println("Persistence: REMOTE_NEO4J requested but NEO4J_URI/NEO4J_USER/"
                            + "NEO4J_PASSWORD not all set; falling back to JSON_FILE at "
                            + config.storagePath().toAbsolutePath());
                    }
                    return from(
                        new PersistenceConfig(
                            GraphPersistenceMode.JSON_FILE,
                            config.storagePath(),
                            null, null, null
                        ),
                        log
                    );
                }
                Neo4jExpressionGraphStore graph = new Neo4jExpressionGraphStore(
                    config.neo4jUri(), config.neo4jUser(), config.neo4jPassword());
                Neo4jRuleInventoryRepository inventory = new Neo4jRuleInventoryRepository(
                    config.neo4jUri(), config.neo4jUser(), config.neo4jPassword());
                Neo4jTranspositionTable table = new Neo4jTranspositionTable(
                    config.neo4jUri(), config.neo4jUser(), config.neo4jPassword());
                if (log != null) {
                    log.println("Persistence: REMOTE_NEO4J at " + config.neo4jUri());
                }
                return new PersistenceContext(GraphPersistenceMode.REMOTE_NEO4J, graph, inventory, table);
            }
            case POSTGRESQL, POSTGRESQL_WITH_JSON_FALLBACK -> {
                Optional<RelationalPersistenceAdapters> adapters = PersistenceAdapterFactory.create(config, log);
                JsonFileExpressionGraphStore graph = new JsonFileExpressionGraphStore(config.storagePath());
                JsonFileRuleInventoryRepository inventory = new JsonFileRuleInventoryRepository(config.storagePath());
                JsonFileTranspositionTable table = new JsonFileTranspositionTable(config.storagePath());
                if (log != null) {
                    if (adapters.isPresent()) {
                        log.println("Persistence: " + config.mode() + " relational metadata at "
                            + config.postgresUrl() + "; mathematical graph artifacts use JSON_FILE at "
                            + graph.filePath().toAbsolutePath());
                    } else {
                        log.println("Persistence: POSTGRESQL_WITH_JSON_FALLBACK running JSON-only at "
                            + graph.filePath().toAbsolutePath());
                    }
                }
                return new PersistenceContext(
                    adapters.isPresent() ? config.mode() : GraphPersistenceMode.JSON_FILE,
                    graph,
                    inventory,
                    table,
                    adapters.orElse(null)
                );
            }
            default -> throw new IllegalStateException("Unhandled mode: " + config.mode());
        }
    }

    @Override
    public void close() {
        closeQuietly(graphStore);
        closeQuietly(inventoryRepository);
        if (transpositionTable instanceof AutoCloseable closable) {
            closeQuietly(closable);
        }
        closeQuietly(relationalAdapters);
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ignored) {
            // Intentionally swallowed: close errors must not block shutdown.
        }
    }
}
