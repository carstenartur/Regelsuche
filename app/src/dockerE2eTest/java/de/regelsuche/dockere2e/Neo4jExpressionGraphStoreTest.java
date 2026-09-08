package de.regelsuche.dockere2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.Neo4jExpressionGraphStore;
import de.regelsuche.transform.RecordedExecution;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Exercises the actual Bolt/Cypher boundary, including stores predating execution identities. */
@Testcontainers(disabledWithoutDocker = true)
class Neo4jExpressionGraphStoreTest {
    private static final String PASSWORD = "regelsuche-test";
    private static final String FROM = "x + 0";
    private static final String TO = "x";

    @Container
    static final GenericContainer<?> NEO4J = new GenericContainer<>(DockerImageName.parse(
        "neo4j:5.26.30-community@sha256:037cf5756f0135cbfd66b739b6df7c7c4bb100f9ce11602f6f9538e17e02c74d"))
        .withEnv("NEO4J_AUTH", "neo4j/" + PASSWORD)
        .withEnv("NEO4J_server_memory_heap_initial__size", "256m")
        .withEnv("NEO4J_server_memory_heap_max__size", "256m")
        .withEnv("NEO4J_server_memory_pagecache_size", "64m")
        .withExposedPorts(7687)
        .waitingFor(Wait.forLogMessage(".*Started\\.\\s", 1))
        .withStartupTimeout(Duration.ofMinutes(2))
        .withCreateContainerCmdModifier(command -> command.getHostConfig()
            .withMemory(1024L * 1024L * 1024L)
            .withMemorySwap(1024L * 1024L * 1024L));

    @BeforeEach
    void clearDatabase() {
        query("MATCH (n) DETACH DELETE n", Map.of());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reusesLegacyOrEmptyIdentityWithoutOverwritingParallelExecutions(boolean initialized) {
        seedLegacy(FROM, TO, "r", "original");
        if (initialized) {
            query("MATCH ()-[r:TRANSFORMATION {marker: 'original'}]->() SET r.executionIdentity = ''", Map.of());
        }
        var first = recordedEdge("application-a");
        var second = recordedEdge("application-b");
        var observation = new GraphEdge(FROM, TO, "r", 7, 9);
        try (var store = store()) {
            store.saveEdge(first);
            store.saveEdge(second);
            assertEquals(3, store.snapshot().edges().size());
            assertTrue(store.snapshot().edges().contains(new GraphEdge(FROM, TO, "r", 0, 0)),
                "Saving recorded executions must leave the legacy observation without invented lineage");

            store.saveEdge(observation);
            store.saveEdge(observation);
            var edges = store.snapshot().edges();
            assertEquals(3, edges.size(), "Repeated saves must reuse the original relationship");
            assertEquals(Set.of(first, second, observation), Set.copyOf(edges));
        }
        var original = query("MATCH ()-[r:TRANSFORMATION {marker: 'original'}]->() RETURN properties(r) AS properties",
            Map.of()).getFirst().get("properties");
        assertEquals("", ((Map<?, ?>) original).get("executionIdentity"));
        assertEquals(7L, ((Map<?, ?>) original).get("depth"));
        assertNull(((Map<?, ?>) original).get("execution"));
    }

    @Test
    void createsObservationAlongsideRecordedEdgesAndRetainsThemAfterReconnect() {
        var first = recordedEdge("application-a");
        var second = recordedEdge("application-b");
        var observation = new GraphEdge(FROM, TO, "r", 1, 1);
        try (var store = store()) {
            store.saveEdge(first);
            store.saveEdge(second);
            store.saveEdge(observation);
            store.saveEdge(first);
            store.saveEdge(observation);
        }
        try (var restarted = store()) {
            assertEquals(3, restarted.snapshot().edges().size());
            assertEquals(Set.of(first, second, observation), Set.copyOf(restarted.snapshot().edges()));
        }
    }

    @Test
    void leavesLegacyRelationshipsForOtherRulesEndpointsAndDirectionsUntouched() {
        seedLegacy(FROM, "y", "r", "other-target");
        seedLegacy("y", TO, "r", "other-source");
        seedLegacy(FROM, TO, "other-rule", "other-rule");
        seedLegacy(TO, FROM, "r", "reverse");
        String legacyQuery = "MATCH (a)-[r:TRANSFORMATION]->(b) WHERE r.marker IS NOT NULL "
            + "RETURN a.value AS source, b.value AS target, properties(r) AS properties ORDER BY r.marker";
        var before = query(legacyQuery, Map.of());
        var observation = new GraphEdge(FROM, TO, "r", 1, 1);
        try (var store = store()) {
            store.saveEdge(observation);
            store.saveEdge(observation);
            assertEquals(5, store.snapshot().edges().size());
            assertTrue(store.snapshot().edges().contains(observation));
        }
        assertEquals(before, query(legacyQuery, Map.of()));
    }

    private static GraphEdge recordedEdge(String applicationKey) {
        var transformation = new Transformation("r", TO, RewriteKind.NORMALIZE, false, 0, true,
            applicationKey, List.of(), "pack", "PROJECT", List.of("r"));
        var execution = RecordedExecution.capture(FROM, List.of(transformation));
        return new GraphEdge(FROM, TO, "r", 1, 1, "path", "canonical", 2, 1,
            RewriteKind.NORMALIZE, false, 0, true, CandidateProofStatus.OBSERVED, null, execution);
    }

    private static void seedLegacy(String from, String to, String rule, String marker) {
        query("MERGE (a:Expression {value: $from}) MERGE (b:Expression {value: $to}) "
            + "CREATE (a)-[:TRANSFORMATION {rule: $rule, marker: $marker}]->(b)",
            Map.of("from", from, "to", to, "rule", rule, "marker", marker));
    }

    private static List<Map<String, Object>> query(String cypher, Map<String, Object> parameters) {
        try (var driver = GraphDatabase.driver(uri(), AuthTokens.basic("neo4j", PASSWORD));
             var session = driver.session()) {
            return session.run(cypher, parameters).list(record -> record.asMap());
        }
    }

    private static Neo4jExpressionGraphStore store() {
        return new Neo4jExpressionGraphStore(uri(), "neo4j", PASSWORD);
    }

    private static String uri() {
        return "bolt://" + NEO4J.getHost() + ":" + NEO4J.getMappedPort(7687);
    }
}
