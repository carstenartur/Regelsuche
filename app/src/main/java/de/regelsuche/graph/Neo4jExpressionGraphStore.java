package de.regelsuche.graph;

import java.util.ArrayList;
import java.util.List;
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
        try (Session session = driver.session()) {
            session.run(
                "MERGE (from:Expression {value: $from}) "
                    + "MERGE (to:Expression {value: $to}) "
                    + "MERGE (from)-[r:TRANSFORMATION {rule: $rule}]->(to) "
                    + "SET r.depth = $depth, r.improvement = $improvement",
                java.util.Map.of(
                    "from", edge.fromExpression(),
                    "to", edge.toExpression(),
                    "rule", edge.transformationRule(),
                    "depth", edge.depth(),
                    "improvement", edge.improvement()
                )
            );
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
                    + "RETURN from.value AS fromExpr, to.value AS toExpr, r.rule AS rule, r.depth AS depth, r.improvement AS improvement"
            );
            List<GraphEdge> edges = new ArrayList<>();
            while (edgeResult.hasNext()) {
                Record record = edgeResult.next();
                edges.add(new GraphEdge(
                    record.get("fromExpr").asString(),
                    record.get("toExpr").asString(),
                    record.get("rule").asString(),
                    record.get("depth").asInt(),
                    record.get("improvement").asInt()
                ));
            }
            return new GraphSnapshot(nodes, edges);
        }
    }

    @Override
    public void close() {
        driver.close();
    }
}
