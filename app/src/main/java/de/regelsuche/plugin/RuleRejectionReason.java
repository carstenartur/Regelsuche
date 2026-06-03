package de.regelsuche.plugin;

public enum RuleRejectionReason {
    DISABLED_BY_CONFIG,
    DISABLED_BY_PROFILE,
    PATTERN_MISMATCH,
    CONDITION_FAILED,
    GROWTH_LIMIT_EXCEEDED,
    CANDIDATE_LIMIT_REACHED,
    CYCLE_RISK,
    APPLIED
}
