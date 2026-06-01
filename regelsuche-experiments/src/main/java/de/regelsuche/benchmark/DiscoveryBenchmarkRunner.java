package de.regelsuche.benchmark;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class DiscoveryBenchmarkRunner {
    public DiscoveryBenchmarkResult run(DiscoveryBenchmarkCase benchmarkCase) {
        Set<String> bridgeRules = new LinkedHashSet<>();
        int macroReuse = 0;
        boolean targetReached = false;
        for (List<String> path : benchmarkCase.candidatePaths()) {
            if (!path.isEmpty() && path.get(path.size() - 1).equals(benchmarkCase.target())) {
                targetReached = true;
            }
            for (String step : path) {
                if (benchmarkCase.bridgeRules().contains(step)) {
                    bridgeRules.add(step);
                }
                if (benchmarkCase.reusableMacros().contains(step)) {
                    macroReuse++;
                }
            }
        }
        int convergences = benchmarkCase.candidatePaths().stream()
                .flatMap(List::stream)
                .collect(Collectors.groupingBy(node -> node, Collectors.counting()))
                .values().stream()
                .filter(count -> count > 1)
                .mapToInt(Long::intValue)
                .sum();
        boolean success = targetReached
                && expectationSatisfied(benchmarkCase, DiscoveryExpectation.BRIDGE_REQUIRED, !bridgeRules.isEmpty())
                && expectationSatisfied(benchmarkCase, DiscoveryExpectation.CONVERGENCE_REQUIRED, convergences > 0)
                && expectationSatisfied(benchmarkCase, DiscoveryExpectation.MACRO_LEARNING_REQUIRED, !benchmarkCase.learnedMacros().isEmpty())
                && expectationSatisfied(benchmarkCase, DiscoveryExpectation.MACRO_REUSE_REQUIRED, macroReuse > 0);
        return new DiscoveryBenchmarkResult(
                success,
                benchmarkCase.candidatePaths().stream().mapToInt(List::size).sum(),
                benchmarkCase.candidatePaths().size(),
                convergences,
                bridgeRules.size(),
                macroReuse,
                List.copyOf(bridgeRules));
    }

    private boolean expectationSatisfied(DiscoveryBenchmarkCase benchmarkCase, DiscoveryExpectation expectation, boolean actual) {
        return !benchmarkCase.expectations().contains(expectation) || actual;
    }
}
