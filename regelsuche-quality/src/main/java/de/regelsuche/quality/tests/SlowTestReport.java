package de.regelsuche.quality.tests;

import java.util.List;

/** Deterministic checkout-owned summary of Gradle JUnit XML durations. */
public record SlowTestReport(
    int suiteCount,
    int testCount,
    double totalTestSeconds,
    double slowThresholdSeconds,
    int slowTestCount,
    List<TestCaseEntry> slowestTests,
    List<TestClassEntry> slowestClasses
) {
    public SlowTestReport {
        slowestTests = slowestTests == null
            ? List.of()
            : List.copyOf(slowestTests);
        slowestClasses = slowestClasses == null
            ? List.of()
            : List.copyOf(slowestClasses);
    }

    public record TestCaseEntry(
        String module,
        String className,
        String testName,
        double seconds,
        boolean failed
    ) { }

    public record TestClassEntry(
        String module,
        String className,
        double seconds,
        int testCount
    ) { }
}
