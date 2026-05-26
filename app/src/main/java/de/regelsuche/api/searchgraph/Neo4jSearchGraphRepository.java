package de.regelsuche.api.searchgraph;

import de.regelsuche.provenance.ProvenanceEdge;
import de.regelsuche.provenance.ProvenanceGraph;
import de.regelsuche.provenance.ProvenanceGraphAssembler;
import de.regelsuche.provenance.ProvenanceNode;
import de.regelsuche.provenance.ProvenanceNodeType;
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
 * <p>Persists each search run twice:</p>
 * <ul>
 *   <li><strong>Snapshot:</strong> the full {@link SearchGraphRecord} JSON is
 *       stored on {@code (:SearchRun.body)} so {@link #findById} and
 *       {@link #findAll} can rehydrate the complete record (including replays,
 *       macro rules, identities and exports).</li>
 *   <li><strong>Native graph:</strong> nodes and edges are also materialised
 *       as
 *       {@code (:SearchRun)-[:HAS_NODE]->(:ExpressionNode)} and
 *       {@code (:ExpressionNode)-[:REWRITES_TO {ruleId, ruleKind, scoreDelta, pathIds, assumptions}]->(:ExpressionNode)}
 *       plus {@code (:TransformationPath)-[:USES_EDGE {stepIndex}]->(:REWRITES_TO)},
 *       {@code (:MacroRuleCandidate)-[:SUPPORTED_BY]->(:TransformationPath)} and
 *       {@code (:IdentityReport)} so the graph becomes Cypher-queryable.</li>
 * </ul>
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
        try (Session session = driver.session()) {
            // 1. Upsert SearchRun + JSON snapshot (so the record is fully round-trippable).
            Map<String, Object> runParams = new HashMap<>();
            runParams.put("id", record.id());
            runParams.put("createdAt", record.createdAt().toString());
            runParams.put("searchProfile", record.searchProfile());
            runParams.put("domains", record.domains());
            runParams.put("body", json);
            session.run(
                "MERGE (r:SearchRun:SearchGraphRecord {id: $id}) "
                    + "SET r.createdAt = $createdAt, r.searchProfile = $searchProfile, "
                    + "r.domains = $domains, r.body = $body",
                runParams
            );
            // 2. Clean previous structured graph for idempotent re-saves of the same run.
            session.run(
                "MATCH (r:SearchRun {id: $id})-[:HAS_NODE|HAS_PATH|HAS_MACRO|HAS_IDENTITY]->(x) "
                    + "DETACH DELETE x",
                Map.of("id", record.id())
            );
            session.run(
                "MATCH (r:SearchRun {id: $id})-[:HAS_PROVENANCE]->(x) DETACH DELETE x",
                Map.of("id", record.id())
            );
            persistGraph(session, record);
            persistProvenanceGraph(session, record);
        }
    }

    private void persistGraph(Session session, SearchGraphRecord record) {
        SearchGraphDto graph = record.graph();
        // Nodes
        for (SearchGraphNodeDto node : graph.nodes()) {
            Map<String, Object> p = new HashMap<>();
            p.put("runId", record.id());
            p.put("id", node.id());
            p.put("expression", node.expression());
            p.put("latex", node.latex());
            p.put("expressionLatex", node.expressionLatex());
            p.put("score", node.score());
            p.put("depth", node.depth());
            p.put("visitedCount", node.visitedCount());
            p.put("isBest", node.isBest());
            p.put("isDeadEnd", node.isDeadEnd());
            p.put("candidateStatus", node.candidateStatus().name());
            p.put("clusterId", node.clusterId());
            session.run(
                "MATCH (r:SearchRun {id: $runId}) "
                    + "MERGE (n:ExpressionNode {runId: $runId, id: $id}) "
                    + "SET n.expression = $expression, n.latex = $latex, "
                    + "n.expressionLatex = $expressionLatex, n.score = $score, "
                    + "n.depth = $depth, n.visitedCount = $visitedCount, n.isBest = $isBest, "
                    + "n.isDeadEnd = $isDeadEnd, n.candidateStatus = $candidateStatus, "
                    + "n.clusterId = $clusterId "
                    + "MERGE (r)-[:HAS_NODE]->(n)",
                p
            );
        }
        // Edges
        for (SearchGraphEdgeDto edge : graph.edges()) {
            Map<String, Object> p = new HashMap<>();
            p.put("runId", record.id());
            p.put("from", edge.from());
            p.put("to", edge.to());
            p.put("ruleId", edge.ruleId());
            p.put("ruleKind", edge.ruleKind().name());
            p.put("scoreDelta", edge.scoreDelta());
            p.put("pathIds", edge.pathIds());
            p.put("assumptions", edge.assumptions());
            p.put("equivalencePreserving", edge.equivalencePreserving());
            session.run(
                "MATCH (from:ExpressionNode {runId: $runId, id: $from}) "
                    + "MATCH (to:ExpressionNode {runId: $runId, id: $to}) "
                    + "MERGE (from)-[e:REWRITES_TO {ruleId: $ruleId}]->(to) "
                    + "SET e.ruleKind = $ruleKind, e.scoreDelta = $scoreDelta, "
                    + "e.pathIds = $pathIds, e.assumptions = $assumptions, "
                    + "e.equivalencePreserving = $equivalencePreserving",
                p
            );
        }
        // Transformation paths from replays
        for (de.regelsuche.api.PathReplayDto replay : record.replays()) {
            Map<String, Object> p = new HashMap<>();
            p.put("runId", record.id());
            p.put("pathId", replay.pathId());
            p.put("stepCount", replay.steps().size());
            session.run(
                "MATCH (r:SearchRun {id: $runId}) "
                    + "MERGE (p:TransformationPath {runId: $runId, id: $pathId}) "
                    + "SET p.stepCount = $stepCount "
                    + "MERGE (r)-[:HAS_PATH]->(p)",
                p
            );
            for (de.regelsuche.api.PathReplayDto.ReplayStep step : replay.steps()) {
                Map<String, Object> ep = new HashMap<>();
                ep.put("runId", record.id());
                ep.put("pathId", replay.pathId());
                ep.put("from", step.fromExpression());
                ep.put("to", step.toExpression());
                ep.put("ruleId", step.ruleId());
                ep.put("stepIndex", step.stepIndex());
                session.run(
                    "MATCH (p:TransformationPath {runId: $runId, id: $pathId}) "
                        + "MATCH (from:ExpressionNode {runId: $runId, id: $from}) "
                        + "MATCH (from)-[e:REWRITES_TO {ruleId: $ruleId}]->"
                        + "(:ExpressionNode {runId: $runId, id: $to}) "
                        + "MERGE (p)-[u:USES_EDGE {stepIndex: $stepIndex}]->(e)",
                    ep
                );
            }
        }
        // Macro rule candidates
        for (de.regelsuche.mining.MacroRuleCandidate macro : record.macroRules()) {
            Map<String, Object> p = new HashMap<>();
            p.put("runId", record.id());
            p.put("id", macro.id());
            p.put("leftPattern", macro.leftPattern());
            p.put("rightPattern", macro.rightPattern());
            p.put("ruleIdSequence", macro.ruleIdSequence());
            p.put("occurrences", macro.occurrences());
            p.put("compressionRatio", macro.compressionRatio());
            p.put("proofStatus", macro.proofStatus().name());
            p.put("supportingTransformationIds", macro.supportingTransformationIds());
            session.run(
                "MATCH (r:SearchRun {id: $runId}) "
                    + "MERGE (m:MacroRuleCandidate {runId: $runId, id: $id}) "
                    + "SET m.leftPattern = $leftPattern, m.rightPattern = $rightPattern, "
                    + "m.ruleIdSequence = $ruleIdSequence, m.occurrences = $occurrences, "
                    + "m.compressionRatio = $compressionRatio, m.proofStatus = $proofStatus, "
                    + "m.supportingTransformationIds = $supportingTransformationIds "
                    + "MERGE (r)-[:HAS_MACRO]->(m) "
                    + "WITH m, $supportingTransformationIds AS pids, $runId AS rid "
                    + "UNWIND pids AS pid "
                    + "MATCH (p:TransformationPath {runId: rid, id: pid}) "
                    + "MERGE (m)-[:SUPPORTED_BY]->(p)",
                p
            );
        }
        // Identity reports
        for (de.regelsuche.api.IdentityReportDto identity : record.identities()) {
            Map<String, Object> p = new HashMap<>();
            p.put("runId", record.id());
            p.put("id", identity.id());
            p.put("leftPattern", identity.leftPattern());
            p.put("rightPattern", identity.rightPattern());
            p.put("ruleIdSequence", identity.ruleIdSequence());
            p.put("occurrences", identity.occurrences());
            p.put("compressionRatio", identity.compressionRatio());
            p.put("proofStatus", identity.proofStatus().name());
            p.put("knownRuleStatus", identity.knownRuleStatus().name());
            p.put("supportingTransformationIds", identity.supportingTransformationIds());
            session.run(
                "MATCH (r:SearchRun {id: $runId}) "
                    + "MERGE (i:IdentityReport {runId: $runId, id: $id}) "
                    + "SET i.leftPattern = $leftPattern, i.rightPattern = $rightPattern, "
                    + "i.ruleIdSequence = $ruleIdSequence, i.occurrences = $occurrences, "
                    + "i.compressionRatio = $compressionRatio, i.proofStatus = $proofStatus, "
                    + "i.knownRuleStatus = $knownRuleStatus, "
                    + "i.supportingTransformationIds = $supportingTransformationIds "
                    + "MERGE (r)-[:HAS_IDENTITY]->(i)",
                p
            );
        }
    }

    private void persistProvenanceGraph(Session session, SearchGraphRecord record) {
        ProvenanceGraph graph = new ProvenanceGraphAssembler().assemble(record);
        for (ProvenanceNode node : graph.nodes()) {
            Map<String, Object> p = new HashMap<>();
            p.put("runId", record.id());
            p.put("id", node.id());
            p.put("type", node.type().name());
            p.put("label", node.label());
            p.put("properties", node.properties().toString());
            p.put("typedProperties", typedProperties(node.properties()));
            session.run(
                "MATCH (r:SearchRun {id: $runId}) "
                    + "MERGE (p:ProvenanceEntity:" + labelFor(node.type()) + " {runId: $runId, id: $id}) "
                    + "SET p.type = $type, p.label = $label, p.properties = $properties "
                    + "SET p += $typedProperties "
                    + "MERGE (r)-[:HAS_PROVENANCE]->(p)",
                p
            );
        }
        for (ProvenanceEdge edge : graph.edges()) {
            Map<String, Object> p = new HashMap<>();
            p.put("runId", record.id());
            p.put("fromId", edge.fromId());
            p.put("toId", edge.toId());
            p.put("edgeType", edge.type().name());
            p.put("properties", edge.properties().toString());
            p.put("typedProperties", typedProperties(edge.properties()));
            session.run(
                "MATCH (from:ProvenanceEntity {runId: $runId, id: $fromId}) "
                    + "MATCH (to:ProvenanceEntity {runId: $runId, id: $toId}) "
                    + "MERGE (from)-[e:" + edge.type().name() + "]->(to) "
                    + "SET e.type = $edgeType, e.properties = $properties "
                    + "SET e += $typedProperties",
                p
            );
        }
    }

    private static Map<String, Object> typedProperties(Map<String, String> properties) {
        Map<String, Object> typed = new HashMap<>();
        if (properties == null) {
            return typed;
        }
        properties.forEach((key, value) -> {
            if (key != null && !key.isBlank()) {
                typed.put(key, typedValue(value));
            }
        });
        return typed;
    }

    private static Object typedValue(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.matches("-?\\d+")) {
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException ignored) {
                return trimmed;
            }
        }
        if (trimmed.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored) {
                return trimmed;
            }
        }
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return Boolean.parseBoolean(trimmed);
        }
        if (trimmed.contains(",")) {
            return java.util.Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .toList();
        }
        return value;
    }

    @Override
    public Optional<SearchGraphRecord> findById(String id) {
        try (Session session = driver.session()) {
            var result = session.run(
                "MATCH (r:SearchRun {id: $id}) RETURN r.body AS body",
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
                "MATCH (r:SearchRun) RETURN r.body AS body, r.createdAt AS createdAt "
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
            // Delete all related structured nodes first, then the SearchRun itself.
            session.run(
                "MATCH (r:SearchRun {id: $id})-[:HAS_NODE|HAS_PATH|HAS_MACRO|HAS_IDENTITY|HAS_PROVENANCE]->(x) "
                    + "DETACH DELETE x",
                Map.of("id", id)
            );
            session.run("MATCH (r:SearchRun {id: $id}) DETACH DELETE r", Map.of("id", id));
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

    private static String labelFor(ProvenanceNodeType type) {
        return switch (type) {
            case HYPOTHESIS -> "Hypothesis";
            case COUNTEREXAMPLE -> "Counterexample";
            case PROOF_ATTEMPT -> "ProofAttempt";
            case SEARCH_RUN -> "ProvenanceSearchRun";
            case MACRO_MOVE -> "MacroMove";
            case SEED_EXPRESSION -> "SeedExpression";
            case ASSUMPTION_SIGNATURE -> "AssumptionSignature";
            case BENCHMARK_RUN -> "BenchmarkRun";
            case TRANSFORMATION_PATH -> "TransformationPath";
            case SYMBOLIC_REGRESSION_PROPOSAL -> "SymbolicRegressionProposal";
            case NUMERIC_RELATION_CANDIDATE -> "NumericRelationCandidate";
            case CAS_VALIDATION_ATTEMPT -> "CasValidationAttempt";
        };
    }
}
