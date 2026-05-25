package de.regelsuche.benchmark;

import java.util.List;

/**
 * Named benchmark scenario with all rows produced for that scenario.
 */
public record BenchmarkScenarioResult(String name, List<SearchBenchmarkResult> results) {
    public BenchmarkScenarioResult {
        results = List.copyOf(results);
    }
}
