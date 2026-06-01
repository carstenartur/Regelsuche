package de.regelsuche.benchmark;

import java.util.List;

public record DiscoveryBenchmarkResult(
        boolean success,
        int statesExplored,
        int pathCount,
        int convergenceCount,
        int bridgeCount,
        int macroReuseCount,
        List<String> bridgeRules) {
    public DiscoveryBenchmarkResult {
        bridgeRules = bridgeRules == null ? List.of() : List.copyOf(bridgeRules);
    }
}
