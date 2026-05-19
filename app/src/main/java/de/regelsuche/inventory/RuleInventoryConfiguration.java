package de.regelsuche.inventory;

import de.regelsuche.mining.CandidateProofStatus;

public record RuleInventoryConfiguration(boolean enabled, CandidateProofStatus minProofStatus) {
    public static RuleInventoryConfiguration disabled() {
        return new RuleInventoryConfiguration(false, CandidateProofStatus.VALIDATED_BY_EXAMPLES);
    }

    public static RuleInventoryConfiguration enabledDefaults() {
        return new RuleInventoryConfiguration(true, CandidateProofStatus.VALIDATED_BY_EXAMPLES);
    }
}
