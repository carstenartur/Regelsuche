package de.regelsuche.plugin;

public record RuleDebugMetadata(
    String ruleId,
    RuleRejectionReason reason,
    String context,
    String detail
) {
    String diagnostic() {
        return ruleId + " " + reason + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }
}
