package de.regelsuche.search.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;

/**
 * Neo4j-backed {@link TranspositionTable} used by the optional Full Mode
 * ({@link de.regelsuche.persistence.GraphPersistenceMode#REMOTE_NEO4J}).
 *
 * <p>Mirrors the {@link de.regelsuche.graph.Neo4jExpressionGraphStore}
 * Driver/Session pattern: opens a single {@link Driver} for the lifetime of
 * the table and uses a short-lived {@link Session} per query. Nodes are
 * labelled {@code TranspositionEntry} and keyed by {@code canonicalHash}.</p>
 */
public final class Neo4jTranspositionTable implements TranspositionTable, AutoCloseable {

    private final Driver driver;

    public Neo4jTranspositionTable(String uri, String username, String password) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }

    @Override
    public Optional<TranspositionEntry> lookup(String canonicalHash) {
        if (canonicalHash == null) {
            return Optional.empty();
        }
        try (Session session = driver.session()) {
            Result result = session.run(
                "MATCH (t:TranspositionEntry {canonicalHash: $hash}) RETURN t",
                Map.of("hash", canonicalHash)
            );
            if (!result.hasNext()) {
                return Optional.empty();
            }
            return Optional.of(toEntry(result.next().get("t")));
        }
    }

    @Override
    public TranspositionEntry record(TranspositionEntry entry) {
        Optional<TranspositionEntry> existing = lookup(entry.canonicalHash());
        TranspositionEntry merged = existing
            .map(e -> e.merge(
                entry.bestScore(),
                entry.minDepthSeen(),
                entry.bestKnownPathId(),
                entry.reachedByRuleIds(),
                entry.lastSeen()))
            .orElse(entry);
        try (Session session = driver.session()) {
            session.run(
                "MERGE (t:TranspositionEntry {canonicalHash: $hash}) "
                    + "SET t.canonicalExpression = $expr, "
                    + "    t.bestScore = $bestScore, "
                    + "    t.minDepthSeen = $minDepth, "
                    + "    t.bestKnownPathId = $bestPathId, "
                    + "    t.reachedByRuleIds = $ruleIds, "
                    + "    t.visitCount = $visitCount, "
                    + "    t.firstSeen = $firstSeen, "
                    + "    t.lastSeen = $lastSeen",
                Map.ofEntries(
                    Map.entry("hash", merged.canonicalHash()),
                    Map.entry("expr", merged.canonicalExpression()),
                    Map.entry("bestScore", merged.bestScore()),
                    Map.entry("minDepth", merged.minDepthSeen()),
                    Map.entry("bestPathId", merged.bestKnownPathId()),
                    Map.entry("ruleIds", new ArrayList<>(merged.reachedByRuleIds())),
                    Map.entry("visitCount", merged.visitCount()),
                    Map.entry("firstSeen", merged.firstSeen().toString()),
                    Map.entry("lastSeen", merged.lastSeen().toString())
                )
            );
        }
        return merged;
    }

    @Override
    public Collection<TranspositionEntry> entries() {
        try (Session session = driver.session()) {
            Result result = session.run("MATCH (t:TranspositionEntry) RETURN t");
            List<TranspositionEntry> all = new ArrayList<>();
            while (result.hasNext()) {
                all.add(toEntry(result.next().get("t")));
            }
            return all;
        }
    }

    @Override
    public int size() {
        try (Session session = driver.session()) {
            Record record = session.run("MATCH (t:TranspositionEntry) RETURN count(t) AS c").single();
            return record.get("c").asInt();
        }
    }

    @Override
    public void clear() {
        try (Session session = driver.session()) {
            session.run("MATCH (t:TranspositionEntry) DETACH DELETE t");
        }
    }

    @Override
    public void close() {
        driver.close();
    }

    private static TranspositionEntry toEntry(Value node) {
        List<String> ruleIds = node.get("reachedByRuleIds").asList(Value::asString);
        return new TranspositionEntry(
            node.get("canonicalHash").asString(),
            node.get("canonicalExpression").asString(""),
            node.get("bestScore").asInt(),
            node.get("minDepthSeen").asInt(),
            node.get("bestKnownPathId").asString(""),
            new LinkedHashSet<>(ruleIds),
            Math.max(1, node.get("visitCount").asInt(1)),
            Instant.parse(node.get("firstSeen").asString(Instant.EPOCH.toString())),
            Instant.parse(node.get("lastSeen").asString(Instant.EPOCH.toString()))
        );
    }
}
