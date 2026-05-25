package de.regelsuche.benchmark;

import java.util.Map;

/** Dashboard-facing aggregate metrics for discovery reports and replay exports. */
public record DiscoveryDashboardMetrics(
    int searchSpaceSize,
    MatchStats matchStats,
    MacroMoveUsage macroMoveUsage,
    long memoryUsage,
    CounterexampleStats counterexampleStats,
    double proofSuccessRate,
    Map<String, Integer> artifactCounts
) {
    public DiscoveryDashboardMetrics {
        matchStats = matchStats == null ? new MatchStats(0, 0) : matchStats;
        macroMoveUsage = macroMoveUsage == null ? new MacroMoveUsage(0, 0, 0.0) : macroMoveUsage;
        counterexampleStats = counterexampleStats == null ? new CounterexampleStats(0, 0) : counterexampleStats;
        artifactCounts = artifactCounts == null ? Map.of() : Map.copyOf(artifactCounts);
    }

    public static DiscoveryDashboardMetrics from(
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        Map<String, Integer> artifactCounts
    ) {
        int processed = report.metrics().processedSeeds();
        int successful = report.metrics().successfulSeeds();
        int replaySteps = report.rows().stream().mapToInt(row -> row.replayPath().size()).sum();
        int macroMentions = (int) report.rows().stream()
            .flatMap(row -> row.replayPath().stream())
            .filter(step -> step.toLowerCase(java.util.Locale.ROOT).contains("macro"))
            .count();
        double proofRate = processed == 0 ? 0.0 : (double) successful / (double) processed;
        return new DiscoveryDashboardMetrics(
            processed + replaySteps,
            new MatchStats(successful, Math.max(0, processed - successful)),
            new MacroMoveUsage(macroMentions, macroMentions, macroMentions),
            report.metrics().accumulatedMemoryBytes(),
            new CounterexampleStats(processed, report.metrics().counterexamples()),
            proofRate,
            artifactCounts
        );
    }

    public record MatchStats(int matched, int unmatched) {
    }

    public record MacroMoveUsage(int considered, int applied, double averageCostReduction) {
    }

    public record CounterexampleStats(int checked, int found) {
    }
}
