package de.regelsuche.benchmarks;

import java.util.List;

public record DiscoveryBenchmarkReport(List<DiscoveryBenchmarkCase> cases) {
    public DiscoveryBenchmarkReport {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public long count(BenchmarkCategory category) {
        return cases.stream().filter(testCase -> testCase.category() == category).count();
    }
}
