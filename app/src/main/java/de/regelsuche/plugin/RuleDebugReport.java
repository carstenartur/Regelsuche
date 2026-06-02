package de.regelsuche.plugin;

import java.util.List;

public record RuleDebugReport(
    String expression,
    List<RuleAttempt> attempts,
    int totalAttempts,
    int successfulApplications,
    int growthLimitRejections,
    int candidateLimitRejections,
    int disabledByConfigRejections,
    int disabledByProfileRejections,
    int conditionFailedRejections,
    int cycleRiskRejections,
    List<String> diagnostics
) {
    public RuleDebugReport {
        attempts = List.copyOf(attempts);
        diagnostics = List.copyOf(diagnostics);
    }
}
