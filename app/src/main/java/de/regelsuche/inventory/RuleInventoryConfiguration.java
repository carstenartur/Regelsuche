package de.regelsuche.inventory;

import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import java.util.Set;

public record RuleInventoryConfiguration(
    boolean enabled,
    CandidateProofStatus minProofStatus,
    int maxComplexityIncrease,
    Set<String> allowedRuleIds,
    Set<String> deniedRuleIds,
    Set<String> disabledRuleIds
) {
    public RuleInventoryConfiguration {
        minProofStatus = minProofStatus == null ? CandidateProofStatus.VALIDATED_BY_EXAMPLES : minProofStatus;
        allowedRuleIds = allowedRuleIds == null ? Set.of() : Set.copyOf(allowedRuleIds);
        deniedRuleIds = deniedRuleIds == null ? Set.of() : Set.copyOf(deniedRuleIds);
        disabledRuleIds = disabledRuleIds == null ? Set.of() : Set.copyOf(disabledRuleIds);
    }

    public RuleInventoryConfiguration(boolean enabled, CandidateProofStatus minProofStatus) {
        this(enabled, minProofStatus, Integer.MAX_VALUE, Set.of(), Set.of(), Set.of());
    }

    public static RuleInventoryConfiguration disabled() {
        return new RuleInventoryConfiguration(false, CandidateProofStatus.VALIDATED_BY_EXAMPLES);
    }

    public static RuleInventoryConfiguration enabledDefaults() {
        return new RuleInventoryConfiguration(true, CandidateProofStatus.VALIDATED_BY_EXAMPLES);
    }

    public RuleInventoryConfiguration withMaxComplexityIncrease(int maxComplexityIncrease) {
        return new RuleInventoryConfiguration(
            enabled,
            minProofStatus,
            maxComplexityIncrease,
            allowedRuleIds,
            deniedRuleIds,
            disabledRuleIds
        );
    }

    public RuleInventoryConfiguration withAllowList(Set<String> allowedRuleIds) {
        return new RuleInventoryConfiguration(
            enabled,
            minProofStatus,
            maxComplexityIncrease,
            allowedRuleIds,
            deniedRuleIds,
            disabledRuleIds
        );
    }

    public RuleInventoryConfiguration withDenyList(Set<String> deniedRuleIds) {
        return new RuleInventoryConfiguration(
            enabled,
            minProofStatus,
            maxComplexityIncrease,
            allowedRuleIds,
            deniedRuleIds,
            disabledRuleIds
        );
    }

    public RuleInventoryConfiguration withDisabledRules(Set<String> disabledRuleIds) {
        return new RuleInventoryConfiguration(
            enabled,
            minProofStatus,
            maxComplexityIncrease,
            allowedRuleIds,
            deniedRuleIds,
            disabledRuleIds
        );
    }

    public boolean allows(String ruleId) {
        if (disabledRuleIds.contains(ruleId)) {
            return false;
        }
        if (deniedRuleIds.contains(ruleId)) {
            return false;
        }
        if (allowedRuleIds.isEmpty()) {
            return true;
        }
        return allowedRuleIds.contains(ruleId);
    }

    public List<String> allowedRuleIdList() {
        return List.copyOf(allowedRuleIds);
    }
}
