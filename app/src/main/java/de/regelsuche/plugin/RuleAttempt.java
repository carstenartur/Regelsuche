package de.regelsuche.plugin;

public record RuleAttempt(
    String ruleId,
    String subtree,
    String phase,
    boolean matched,
    RuleRejectionReason reason,
    String detail
) {
    public RuleAttempt(String ruleId, String subtree, String phase, boolean matched, RuleRejectionReason reason) {
        this(ruleId, subtree, phase, matched, reason, "");
    }
}
