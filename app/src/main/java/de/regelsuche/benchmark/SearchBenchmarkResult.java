package de.regelsuche.benchmark;

public record SearchBenchmarkResult(
    String strategyName,
    String expression,
    int exploredStates,
    int bestImprovement,
    int shortestImprovingDepth,
    int expandedSteps,
    int distinctRules
) {
}
