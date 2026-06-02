package de.regelsuche.plugin;

public record RuleAttempt(
    String ruleId,
    String subtree,
    String phase,
    boolean matched,
    RuleRejectionReason reason
) {}
