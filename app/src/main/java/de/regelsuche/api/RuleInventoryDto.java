package de.regelsuche.api;

import de.regelsuche.inventory.ReusableRule;
import java.time.Instant;
import java.util.List;

public record RuleInventoryDto(
    String id,
    String leftPattern,
    String rightPattern,
    List<String> parameterRelations,
    String proofStatus,
    String knownRuleStatus,
    int supportingExamples,
    double averageImprovement,
    Instant createdAt
) {
    public static RuleInventoryDto from(ReusableRule rule) {
        return new RuleInventoryDto(
            rule.id(),
            rule.leftPattern(),
            rule.rightPattern(),
            rule.parameterRelations(),
            rule.proofStatus().name(),
            rule.knownRuleStatus().name(),
            rule.supportingExamples(),
            rule.averageImprovement(),
            rule.createdAt()
        );
    }
}
