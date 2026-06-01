package de.regelsuche.benchmarks;

import java.util.List;

public final class DiscoveryBenchmarkSuite {
    public DiscoveryBenchmarkReport defaultSuite() {
        return new DiscoveryBenchmarkReport(List.of(
                new DiscoveryBenchmarkCase("factor.diff_squares", BenchmarkCategory.FACTORIZATION, "x^2 - y^2", "(x - y) * (x + y)"),
                new DiscoveryBenchmarkCase("factor.sum_cubes", BenchmarkCategory.FACTORIZATION, "x^3 + y^3", "(x + y) * (x^2 - x*y + y^2)"),
                new DiscoveryBenchmarkCase("factor.sophie_germain", BenchmarkCategory.FACTORIZATION, "x^4 + 4*y^4", "(x^2 - 2*x*y + 2*y^2) * (x^2 + 2*x*y + 2*y^2)"),
                new DiscoveryBenchmarkCase("trig.pythagorean", BenchmarkCategory.TRIGONOMETRY, "sin(x)^2 + cos(x)^2", "1"),
                new DiscoveryBenchmarkCase("trig.sin_double", BenchmarkCategory.TRIGONOMETRY, "sin(2*x)", "2*sin(x)*cos(x)"),
                new DiscoveryBenchmarkCase("rational.telescoping", BenchmarkCategory.RATIONAL, "1/(n*(n + 1))", "1/n - 1/(n + 1)"),
                new DiscoveryBenchmarkCase("complete_square.quadratic", BenchmarkCategory.COMPLETE_SQUARE, "x^2 + 6*x + 5", "(x + 3)^2 - 4")));
    }
}
