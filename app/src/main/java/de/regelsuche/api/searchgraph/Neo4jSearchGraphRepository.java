package de.regelsuche.api.searchgraph;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;

/**
 * Neo4j-backed {@link SearchGraphRepository}.
 *
 * <p>Each {@link SearchGraphRecord} is stored as a single
 * {@code (:SearchGraphRecord {id, createdAt, searchProfile, body})} node
 * where {@code body} is the JSON produced by {@link SearchGraphRecordCodec}.
 * This mirrors the conservative storage strategy already used by
 * {@link de.regelsuche.inventory.Neo4jRuleInventoryRepository} and
 * {@link de.regelsuche.graph.Neo4jExpressionGraphStore}, while letting the
 * graph database act as a durable index over search-graph snapshots.</p>
 *
 * <p>Reuses the project's existing {@code org.neo4j.driver:neo4j-java-driver}
 * dependency — no new libraries.</p>
 */
public final class Neo4jSearchGraphRepository implements SearchGraphRepository {

    private final Driver driver;

    public Neo4jSearchGraphRepository(String uri, String username, String password) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }

    /** Test-only seam: provide an existing {@link Driver} (e.g. embedded harness). */
    public Neo4jSearchGraphRepository(Driver driver) {
        this.driver = driver;
    }

    @Override
    public void save(SearchGraphRecord record) {
        String json = SearchGraphRecordCodec.toJson(record);
        Map<String, Object> params = new HashMap<>();
        params.put("id", record.id());
        params.put("createdAt", record.createdAt().toString());
        params.put("searchProfile", record.searchProfile());
        params.put("domains", record.domains());
        params.put("body", json);
        try (Session session = driver.session()) {
            session.run(
                "MERGE (r:SearchGraphRecord {id: $id}) "
                    + "SET r.createdAt = $createdAt, r.searchProfile = $searchProfile, "
                    + "r.domains = $domains, r.body = $body",
                params
            );
        }
    }

    @Override
    public Optional<SearchGraphRecord> findById(String id) {
        try (Session session = driver.session()) {
            var result = session.run(
                "MATCH (r:SearchGraphRecord {id: $id}) RETURN r.body AS body",
                Map.of("id", id)
            );
            if (!result.hasNext()) {
                return Optional.empty();
            }
            String body = result.next().get("body").asString("");
            return body.isBlank() ? Optional.empty() : Optional.of(SearchGraphRecordCodec.fromJson(body));
        }
    }

    @Override
    public List<SearchGraphRecord> findAll() {
        try (Session session = driver.session()) {
            var result = session.run(
                "MATCH (r:SearchGraphRecord) RETURN r.body AS body, r.createdAt AS createdAt "
                    + "ORDER BY r.createdAt ASC"
            );
            List<SearchGraphRecord> records = new ArrayList<>();
            while (result.hasNext()) {
                Record row = result.next();
                String body = row.get("body").asString("");
                if (!body.isBlank()) {
                    records.add(SearchGraphRecordCodec.fromJson(body));
                }
            }
            return records;
        }
    }

    @Override
    public void delete(String id) {
        try (Session session = driver.session()) {
            session.run("MATCH (r:SearchGraphRecord {id: $id}) DETACH DELETE r", Map.of("id", id));
        }
    }

    @Override
    public void close() {
        driver.close();
    }

    /** Unused helper retained for symmetry with other repos that timestamp accesses. */
    @SuppressWarnings("unused")
    private static Instant nowOr(Instant supplied) {
        return supplied == null ? Instant.now() : supplied;
    }
}
