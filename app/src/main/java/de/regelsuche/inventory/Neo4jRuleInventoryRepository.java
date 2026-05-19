package de.regelsuche.inventory;

import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            session.run(
                "MERGE (rule:ReusableRule {id: $id}) "
                    + "SET rule.leftPattern = $leftPattern, rule.rightPattern = $rightPattern, "
                    + "rule.parameterRelations = $parameterRelations, rule.proofStatus = $proofStatus, "
                    + "rule.knownRuleStatus = $knownRuleStatus, rule.supportingExamples = $supportingExamples, "
                    + "rule.averageImprovement = $averageImprovement, rule.createdAt = $createdAt",
                Map.of(
                    "id", rule.id(),
                    "leftPattern", rule.leftPattern(),
                    "rightPattern", rule.rightPattern(),
                    "parameterRelations", rule.parameterRelations(),
                    "proofStatus", rule.proofStatus().name(),
                    "knownRuleStatus", rule.knownRuleStatus().name(),
                    "supportingExamples", rule.supportingExamples(),
                    "averageImprovement", rule.averageImprovement(),
                    "createdAt", rule.createdAt().toString()
                )
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
                    + "rule.createdAt AS createdAt ORDER BY id"
            );
            List<ReusableRule> rules = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                List<String> relations = record.get("parameterRelations").isNull()
                    ? List.of()
                    : record.get("parameterRelations").asList(Value::asString);
                rules.add(new ReusableRule(
                    record.get("id").asString(),
                    record.get("leftPattern").asString(),
                    record.get("rightPattern").asString(),
                    relations,
                    CandidateProofStatus.valueOf(record.get("proofStatus").asString(CandidateProofStatus.OBSERVED.name())),
                    RuleStatus.valueOf(record.get("knownRuleStatus").asString(RuleStatus.NEW.name())),
                    record.get("supportingExamples").asInt(0),
                    record.get("averageImprovement").asDouble(0),
                    Instant.parse(record.get("createdAt").asString(Instant.EPOCH.toString()))
                ));
            }
            return rules;
        }
    }

    @Override
    public void close() {
        driver.close();
    }
}
