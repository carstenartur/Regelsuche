package de.regelsuche.benchmarks;

public record DiscoveryBenchmarkCase(
        String id,
        BenchmarkCategory category,
        String startExpression,
        String targetExpression) {
}
