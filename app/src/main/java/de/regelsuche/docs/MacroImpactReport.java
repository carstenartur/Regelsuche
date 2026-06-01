package de.regelsuche.docs;

import de.regelsuche.benchmark.DiscoveryBenchmarkResult;
import de.regelsuche.search.SearchSpaceAnalytics;
import java.util.List;

public record MacroImpactReport(
        String caseName,
        int withoutMacroStates,
        int withMacroStates,
        int pathsExplored,
        int convergenceCount,
        int bridgeUsage,
        boolean bridgeDiscovered,
        boolean macroReused,
        String inputExpression,
        String targetExpression,
        List<String> withoutMacroPath,
        List<String> withMacroPath,
        SearchSpaceAnalytics withoutMacroAnalytics,
        SearchSpaceAnalytics withMacroAnalytics,
        DiscoveryBenchmarkResult withoutMacroBenchmark,
        DiscoveryBenchmarkResult withMacroBenchmark) {
    public MacroImpactReport {
        withoutMacroPath = withoutMacroPath == null ? List.of() : List.copyOf(withoutMacroPath);
        withMacroPath = withMacroPath == null ? List.of() : List.copyOf(withMacroPath);
    }

    public double improvementFactor() {
        return withoutMacroStates / (double) withMacroStates;
    }
}
