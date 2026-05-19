package de.regelsuche.inventory;

import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleStatus;
import java.time.Instant;
import java.util.List;

public record ReusableRule(
    String id,
    String leftPattern,
    String rightPattern,
    List<String> parameterRelations,
    CandidateProofStatus proofStatus,
    RuleStatus knownRuleStatus,
    int supportingExamples,
    double averageImprovement,
    Instant createdAt
) {
    public ReusableRule {
        if (id == null || id.isBlank() || leftPattern == null || rightPattern == null) {
            throw new IllegalArgumentException("id and patterns are required");
        }
        parameterRelations = List.copyOf(parameterRelations);
        proofStatus = proofStatus == null ? CandidateProofStatus.OBSERVED : proofStatus;
        knownRuleStatus = knownRuleStatus == null ? RuleStatus.NEW : knownRuleStatus;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
