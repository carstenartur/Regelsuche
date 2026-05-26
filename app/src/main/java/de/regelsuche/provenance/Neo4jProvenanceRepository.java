package de.regelsuche.provenance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

public final class Neo4jProvenanceRepository implements ProvenanceRepository, AutoCloseable {
    private final Driver driver;

    public Neo4jProvenanceRepository(Driver driver) {
        this.driver = java.util.Objects.requireNonNull(driver);
    }

    @Override
    public void save(String runId, ProvenanceGraph graph) {
        try (Session session = driver.session()) {
            session.run("MERGE (r:SearchRun {id: $runId})", Map.of("runId", runId));
            session.run(
                "MATCH (r:SearchRun {id: $runId})-[:HAS_PROVENANCE]->(x) DETACH DELETE x",
                Map.of("runId", runId)
            );
            for (ProvenanceNode node : graph.nodes()) {
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("runId", runId);
                parameters.put("id", node.id());
                parameters.put("type", node.type().name());
                parameters.put("label", node.label());
                parameters.put("propertyKeys", new ArrayList<>(node.properties().keySet()));
                parameters.put("propertyValues", new ArrayList<>(node.properties().values()));
                session.run(
                    "MATCH (r:SearchRun {id: $runId}) "
                        + "MERGE (p:ProvenanceEntity {runId: $runId, id: $id}) "
                        + "SET p.type = $type, p.label = $label, p.propertyKeys = $propertyKeys, "
                        + "p.propertyValues = $propertyValues "
                        + "MERGE (r)-[:HAS_PROVENANCE]->(p)",
                    parameters
                );
            }
            for (ProvenanceEdge edge : graph.edges()) {
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("runId", runId);
                parameters.put("fromId", edge.fromId());
                parameters.put("toId", edge.toId());
                parameters.put("type", edge.type().name());
                parameters.put("propertyKeys", new ArrayList<>(edge.properties().keySet()));
                parameters.put("propertyValues", new ArrayList<>(edge.properties().values()));
                session.run(
                    "MATCH (from:ProvenanceEntity {runId: $runId, id: $fromId}) "
                        + "MATCH (to:ProvenanceEntity {runId: $runId, id: $toId}) "
                        + "MERGE (from)-[e:" + edge.type().name() + "]->(to) "
                        + "SET e.type = $type, e.propertyKeys = $propertyKeys, e.propertyValues = $propertyValues",
                    parameters
                );
            }
        }
    }

    @Override
    public Optional<ProvenanceGraph> findByRunId(String runId) {
        try (Session session = driver.session()) {
            var nodeRows = session.run(
                "MATCH (:SearchRun {id: $runId})-[:HAS_PROVENANCE]->(p:ProvenanceEntity) "
                    + "RETURN p.id AS id, p.type AS type, p.label AS label, "
                    + "p.propertyKeys AS propertyKeys, p.propertyValues AS propertyValues ORDER BY p.id",
                Map.of("runId", runId)
            );
            List<ProvenanceNode> nodes = new ArrayList<>();
            while (nodeRows.hasNext()) {
                var row = nodeRows.next();
                nodes.add(new ProvenanceNode(
                    row.get("id").asString(),
                    ProvenanceNodeType.valueOf(row.get("type").asString()),
                    row.get("label").asString(),
                    stringMap(row.get("propertyKeys").asList(), row.get("propertyValues").asList())
                ));
            }
            if (nodes.isEmpty()) {
                return Optional.empty();
            }
            var edgeRows = session.run(
                "MATCH (from:ProvenanceEntity {runId: $runId})-[e]->(to:ProvenanceEntity {runId: $runId}) "
                    + "WHERE e.type IS NOT NULL "
                    + "RETURN from.id AS fromId, to.id AS toId, e.type AS type, "
                    + "e.propertyKeys AS propertyKeys, e.propertyValues AS propertyValues "
                    + "ORDER BY from.id, to.id, e.type",
                Map.of("runId", runId)
            );
            List<ProvenanceEdge> edges = new ArrayList<>();
            while (edgeRows.hasNext()) {
                var row = edgeRows.next();
                edges.add(new ProvenanceEdge(
                    row.get("fromId").asString(),
                    row.get("toId").asString(),
                    ProvenanceEdgeType.valueOf(row.get("type").asString()),
                    stringMap(row.get("propertyKeys").asList(), row.get("propertyValues").asList())
                ));
            }
            return Optional.of(new ProvenanceGraph(nodes, edges));
        }
    }

    @Override
    public void close() {
        driver.close();
    }

    private static Map<String, String> stringMap(List<Object> keys, List<Object> values) {
        if (keys == null || values == null || keys.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < Math.min(keys.size(), values.size()); i++) {
            result.put(String.valueOf(keys.get(i)), values.get(i) == null ? "" : values.get(i).toString());
        }
        return result;
    }
}
