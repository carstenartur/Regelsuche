package de.regelsuche.plugin;

import java.util.List;

public record RuleDebugReport(
    String expression,
    List<RuleAttempt> attempts,
    int totalAttempts,
    int successfulApplications,
    int growthLimitRejections,
    int candidateLimitRejections
) {
    public RuleDebugReport {
        attempts = List.copyOf(attempts);
    }
}
