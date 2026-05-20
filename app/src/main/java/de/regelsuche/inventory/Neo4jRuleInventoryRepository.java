package de.regelsuche.inventory;

import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;

public class Neo4jRuleInventoryRepository implements RuleInventoryRepository {
    private final Driver driver;

    public Neo4jRuleInventoryRepository(String uri, String username, String password) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }

    @Override
    public void save(ReusableRule rule) {
        try (Session session = driver.session()) {
            java.util.HashMap<String, Object> params = new java.util.HashMap<>();
            params.put("id", rule.id());
            params.put("leftPattern", rule.leftPattern());
            params.put("rightPattern", rule.rightPattern());
            params.put("parameterRelations", rule.parameterRelations());
            params.put("proofStatus", rule.proofStatus().name());
            params.put("knownRuleStatus", rule.knownRuleStatus().name());
            params.put("supportingExamples", rule.supportingExamples());
            params.put("averageImprovement", rule.averageImprovement());
            params.put("createdAt", rule.createdAt().toString());
            params.put("canonicalHash", rule.canonicalHash() == null ? "" : rule.canonicalHash());
            params.put("usageCount", rule.usageCount());
            params.put("lastUsedAt", rule.lastUsedAt() == null ? null : rule.lastUsedAt().toString());
            session.run(
                "MERGE (rule:ReusableRule {id: $id}) "
                    + "SET rule.leftPattern = $leftPattern, rule.rightPattern = $rightPattern, "
                    + "rule.parameterRelations = $parameterRelations, rule.proofStatus = $proofStatus, "
                    + "rule.knownRuleStatus = $knownRuleStatus, rule.supportingExamples = $supportingExamples, "
                    + "rule.averageImprovement = $averageImprovement, rule.createdAt = $createdAt, "
                    + "rule.canonicalHash = $canonicalHash, "
                    + "rule.usageCount = $usageCount, "
                    + "rule.lastUsedAt = $lastUsedAt, "
                    + "rule.enabled = coalesce(rule.enabled, true), "
                    + "rule.tags = coalesce(rule.tags, [])",
                params
            );
        }
    }

    @Override
    public List<ReusableRule> findAll() {
        try (Session session = driver.session()) {
            var result = session.run(
                "MATCH (rule:ReusableRule) RETURN rule.id AS id, rule.leftPattern AS leftPattern, "
                    + "rule.rightPattern AS rightPattern, rule.parameterRelations AS parameterRelations, "
                    + "rule.proofStatus AS proofStatus, rule.knownRuleStatus AS knownRuleStatus, "
                    + "rule.supportingExamples AS supportingExamples, rule.averageImprovement AS averageImprovement, "
                    + "rule.createdAt AS createdAt, rule.canonicalHash AS canonicalHash, "
                    + "rule.usageCount AS usageCount, rule.lastUsedAt AS lastUsedAt "
                    + "ORDER BY id"
            );
            List<ReusableRule> rules = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                List<String> relations = record.get("parameterRelations").isNull()
                    ? List.of()
                    : record.get("parameterRelations").asList(Value::asString);
                String lastUsedRaw = record.get("lastUsedAt").asString("");
                Instant lastUsed = lastUsedRaw.isBlank() ? null : Instant.parse(lastUsedRaw);
                rules.add(new ReusableRule(
                    record.get("id").asString(),
                    record.get("leftPattern").asString(),
                    record.get("rightPattern").asString(),
                    relations,
                    CandidateProofStatus.valueOf(record.get("proofStatus").asString(CandidateProofStatus.OBSERVED.name())),
                    RuleStatus.valueOf(record.get("knownRuleStatus").asString(RuleStatus.NEW.name())),
                    record.get("supportingExamples").asInt(0),
                    record.get("averageImprovement").asDouble(0),
                    Instant.parse(record.get("createdAt").asString(Instant.EPOCH.toString())),
                    record.get("canonicalHash").asString(""),
                    lastUsed,
                    record.get("usageCount").asInt(0)
                ));
            }
            return rules;
        }
    }

    /**
     * Increment {@code usageCount} and refresh {@code lastUsedAt} on the
     * Neo4j node. Idempotent on missing rule ids (no-op).
     */
    public void recordUsage(String ruleId, Instant when) {
        try (Session session = driver.session()) {
            session.run(
                "MATCH (rule:ReusableRule {id: $id}) "
                    + "SET rule.usageCount = coalesce(rule.usageCount, 0) + 1, "
                    + "rule.lastUsedAt = $when",
                Map.of("id", ruleId, "when", when.toString())
            );
        }
    }

    @Override
    public void setEnabled(String ruleId, boolean enabled) {
        try (Session session = driver.session()) {
            session.run(
                "MATCH (rule:ReusableRule {id: $id}) SET rule.enabled = $enabled",
                Map.of("id", ruleId, "enabled", enabled)
            );
        }
    }

    @Override
    public boolean isEnabled(String ruleId) {
        try (Session session = driver.session()) {
            var result = session.run(
                "MATCH (rule:ReusableRule {id: $id}) RETURN coalesce(rule.enabled, true) AS enabled",
                Map.of("id", ruleId)
            );
            if (result.hasNext()) {
                return result.next().get("enabled").asBoolean(true);
            }
            return true;
        }
    }

    @Override
    public void addTag(String ruleId, String tag) {
        try (Session session = driver.session()) {
            session.run(
                "MATCH (rule:ReusableRule {id: $id}) "
                    + "SET rule.tags = coalesce(rule.tags, []) + "
                    + "CASE WHEN $tag IN coalesce(rule.tags, []) THEN [] ELSE [$tag] END",
                Map.of("id", ruleId, "tag", tag)
            );
        }
    }

    @Override
    public void removeTag(String ruleId, String tag) {
        try (Session session = driver.session()) {
            session.run(
                "MATCH (rule:ReusableRule {id: $id}) "
                    + "SET rule.tags = [t IN coalesce(rule.tags, []) WHERE t <> $tag]",
                Map.of("id", ruleId, "tag", tag)
            );
        }
    }

    @Override
    public Set<String> tagsOf(String ruleId) {
        try (Session session = driver.session()) {
            var result = session.run(
                "MATCH (rule:ReusableRule {id: $id}) RETURN coalesce(rule.tags, []) AS tags",
                Map.of("id", ruleId)
            );
            if (result.hasNext()) {
                List<String> tags = result.next().get("tags").asList(Value::asString);
                return new LinkedHashSet<>(tags);
            }
            return Set.of();
        }
    }

    @Override
    public void close() {
        driver.close();
    }
}
