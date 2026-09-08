package de.regelsuche.graph;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.RuleCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

public class Neo4jExpressionGraphStore implements ExpressionGraphStore {
    private final Driver driver;

    public Neo4jExpressionGraphStore(String uri, String username, String password) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }

    @Override
    public void saveNode(String expression, int complexity) {
        try (Session session = driver.session()) {
            session.run(
                "MERGE (e:Expression {value: $value}) SET e.complexity = $complexity",
                java.util.Map.of("value", expression, "complexity", complexity)
            );
        }
    }

    @Override
    public void saveEdge(GraphEdge edge) {
        // Legacy observation-only relationships have neither property. Normalize only those
        // before the identity-aware MERGE; matching on rule alone could erase a recorded execution.
        String legacyObservation = edge.execution() == null
            ? "WITH from, to "
                + "OPTIONAL MATCH (from)-[legacy:TRANSFORMATION {rule: $rule}]->(to) "
                + "WHERE legacy.executionIdentity IS NULL AND legacy.execution IS NULL "
                + "SET legacy.executionIdentity = '' "
                + "WITH DISTINCT from, to "
            : "";
        try (Session session = driver.session()) {
            session.run(
                "MERGE (from:Expression {value: $from}) "
                    + "MERGE (to:Expression {value: $to}) "
                    + legacyObservation
                    + "MERGE (from)-[r:TRANSFORMATION {rule: $rule, executionIdentity: $executionIdentity}]->(to) "
                    + "SET r.depth = $depth, r.improvement = $improvement, "
                    + "r.pathId = $pathId, r.canonicalHash = $canonicalHash, "
                    + "r.scoreBefore = $scoreBefore, r.scoreAfter = $scoreAfter, "
                    + "r.rewriteKind = $rewriteKind, r.mayIncreaseComplexity = $mayIncreaseComplexity, "
                    + "r.estimatedCostDelta = $estimatedCostDelta, "
                    + "r.equivalencePreservingByConstruction = $equivalencePreservingByConstruction, "
                    + "r.validationStatus = $validationStatus, r.execution = $execution",
                GraphEdgePersistence.properties(edge)
            ).consume();
        }
    }

    @Override
    public GraphSnapshot snapshot() {
        try (Session session = driver.session()) {
            Result nodeResult = session.run("MATCH (e:Expression) RETURN e.value AS value");
            List<String> nodes = new ArrayList<>();
            while (nodeResult.hasNext()) {
                Record record = nodeResult.next();
                nodes.add(record.get("value").asString());
            }

            Result edgeResult = session.run(
                "MATCH (from:Expression)-[r:TRANSFORMATION]->(to:Expression) "
                    + "RETURN from.value AS fromExpr, to.value AS toExpr, properties(r) AS properties"
            );
            List<GraphEdge> edges = new ArrayList<>();
            while (edgeResult.hasNext()) {
                Record record = edgeResult.next();
                edges.add(GraphEdgePersistence.read(
                    record.get("fromExpr").asString(),
                    record.get("toExpr").asString(),
                    record.get("properties").asMap()
                ));
            }
            return new GraphSnapshot(nodes, edges);
        }
    }

    @Override
    public void saveDiscoveredTransformation(DiscoveredTransformation transformation) {
        try (Session session = driver.session()) {
            session.run(
                "MERGE (start:Expression {value: $original}) "
                    + "SET start.canonicalHash = $canonicalHash, start.score = $originalScore "
                    + "MERGE (end:Expression {value: $improved}) "
                    + "SET end.canonicalHash = $canonicalHash, end.score = $improvedScore "
                    + "MERGE (path:TransformationPath {id: $id}) "
                    + "SET path.discoveredAt = $discoveredAt, path.totalImprovement = $totalImprovement, "
                    + "path.status = $status, path.canonicalHash = $canonicalHash "
                    + "MERGE (path)-[:STARTS_WITH]->(start) "
                    + "MERGE (path)-[:ENDS_WITH]->(end)",
                Map.of(
                    "id", transformation.id(),
                    "original", transformation.originalExpression(),
                    "improved", transformation.improvedExpression(),
                    "originalScore", transformation.originalScore().weightedTotal(),
                    "improvedScore", transformation.improvedScore().weightedTotal(),
                    "discoveredAt", transformation.discoveredAt().toString(),
                    "totalImprovement", transformation.totalImprovement(),
                    "status", transformation.validationStatus().name(),
                    "canonicalHash", transformation.canonicalHash()
                )
            );
            for (TransformationStep step : transformation.steps()) {
                session.run(
                    "MATCH (path:TransformationPath {id: $pathId}) "
                        + "MERGE (from:Expression {value: $before}) "
                        + "MERGE (to:Expression {value: $after}) "
                        + "MERGE (step:TransformationStep {pathId: $pathId, index: $index}) "
                        + "SET step.ruleId = $ruleId, step.ruleKind = $ruleKind, step.explanation = $explanation, "
                        + "step.scoreBefore = $scoreBefore, step.scoreAfter = $scoreAfter, "
                        + "step.equivalencePreserving = $equivalencePreserving, step.execution = $execution, "
                        + "step.assumptions = $assumptions "
                        + "MERGE (path)-[:HAS_STEP]->(step) "
                        + "MERGE (step)-[:FROM]->(from) "
                        + "MERGE (step)-[:TO]->(to)",
                    stepProperties(transformation.id(), step)
                );
            }
        }
    }

    private static Map<String, Object> stepProperties(String pathId, TransformationStep step) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("pathId", pathId); values.put("index", step.index());
        values.put("before", step.beforeExpression()); values.put("after", step.afterExpression());
        values.put("ruleId", step.ruleId()); values.put("ruleKind", step.ruleKind().name());
        values.put("explanation", step.explanation()); values.put("scoreBefore", step.scoreBefore());
        values.put("scoreAfter", step.scoreAfter()); values.put("equivalencePreserving", step.equivalencePreserving());
        values.put("assumptions", step.assumptions());
        values.put("execution", step.execution() == null ? null : step.execution().toCanonicalJson());
        return values;
    }

    @Override
    public void saveRuleCandidate(RuleCandidate candidate) {
        try (Session session = driver.session()) {
            session.run(
                "MERGE (candidate:RuleCandidate {canonicalHash: $canonicalHash}) "
                    + "SET candidate.leftPattern = $leftPattern, candidate.rightPattern = $rightPattern, "
                    + "candidate.proofStatus = $proofStatus, candidate.status = $status",
                Map.of(
                    "canonicalHash", candidate.canonicalHash(),
                    "leftPattern", candidate.leftPattern(),
                    "rightPattern", candidate.rightPattern(),
                    "proofStatus", candidate.proofStatus().name(),
                    "status", candidate.status().name()
                )
            );
        }
    }

    @Override
    public void saveReusableRule(ReusableRule rule) {
        try (Session session = driver.session()) {
            session.run(
                "MERGE (rule:ReusableRule {id: $id}) "
                    + "SET rule.leftPattern = $leftPattern, rule.rightPattern = $rightPattern, "
                    + "rule.status = $status, rule.proofStatus = $proofStatus, rule.createdAt = $createdAt",
                Map.of(
                    "id", rule.id(),
                    "leftPattern", rule.leftPattern(),
                    "rightPattern", rule.rightPattern(),
                    "status", rule.knownRuleStatus().name(),
                    "proofStatus", rule.proofStatus().name(),
                    "createdAt", rule.createdAt().toString()
                )
            );
        }
    }

    @Override
    public void close() {
        driver.close();
    }
}
