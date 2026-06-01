package de.regelsuche.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DiscoveryBenchmarkSuiteTest {
    @Test
    void exposesRequestedCategories() {
        DiscoveryBenchmarkReport report = new DiscoveryBenchmarkSuite().defaultSuite();

        assertTrue(report.count(BenchmarkCategory.FACTORIZATION) >= 3);
        assertTrue(report.count(BenchmarkCategory.TRIGONOMETRY) >= 2);
        assertEquals(1, report.count(BenchmarkCategory.RATIONAL));
        assertEquals(1, report.count(BenchmarkCategory.COMPLETE_SQUARE));
    }
}
