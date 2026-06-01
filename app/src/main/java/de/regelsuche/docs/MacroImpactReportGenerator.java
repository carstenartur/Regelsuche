package de.regelsuche.docs;

import de.regelsuche.benchmark.DiscoveryBenchmarkCase;
import de.regelsuche.benchmark.DiscoveryBenchmarkResult;
import de.regelsuche.benchmark.DiscoveryBenchmarkRunner;
import de.regelsuche.benchmark.DiscoveryExpectation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MacroImpactReportGenerator {
    private final DiscoveryBenchmarkExecutor executor;
    private final DiscoveryBenchmarkScenarioLoader scenarioLoader;

    public MacroImpactReportGenerator() {
        this(new DiscoveryBenchmarkExecutor(), new DiscoveryBenchmarkScenarioLoader());
    }

    MacroImpactReportGenerator(DiscoveryBenchmarkExecutor executor, DiscoveryBenchmarkScenarioLoader scenarioLoader) {
        this.executor = executor;
        this.scenarioLoader = scenarioLoader;
    }

    public MacroImpactReport generate() {
        return generate(scenarioLoader.load("discovery-scenarios/complete-square.yaml"));
    }

    public MacroImpactReport generate(DiscoveryBenchmarkScenario scenario) {
        DiscoveryBenchmarkEvidence evidence = executor.execute(scenario);
        DiscoveryBenchmarkResult withoutBenchmark = benchmark(
                scenario.id() + "-without-macro",
                scenario,
                evidence.withoutMacroRun().appliedRuleIds(),
                evidence.bridgeRulesUsed(),
                Set.of(),
                Set.of(),
                Set.of(DiscoveryExpectation.BRIDGE_REQUIRED));
        DiscoveryBenchmarkResult withBenchmark = benchmark(
                scenario.id() + "-with-macro",
                scenario,
                evidence.withMacroRun().appliedRuleIds(),
                List.of(),
                new LinkedHashSet<>(evidence.learnedMacros()),
                new LinkedHashSet<>(evidence.learnedMacros()),
                scenario.macroLearning().enabled() ? Set.of(DiscoveryExpectation.MACRO_REUSE_REQUIRED) : Set.of());
        return new MacroImpactReport(
                scenario.id(),
                scenario.displayName(),
                evidence.withoutMacroRun().analytics().statesExplored(),
                evidence.withMacroRun().analytics().statesExplored(),
                withoutBenchmark.pathCount() + withBenchmark.pathCount(),
                evidence.convergentStates().size(),
                evidence.bridgeRulesUsed().size(),
                !evidence.bridgeRulesUsed().isEmpty(),
                !evidence.reusedMacros().isEmpty(),
                evidence.inputExpression(),
                evidence.targetExpression(),
                evidence.withoutMacroRun().path(),
                evidence.withMacroRun().path(),
                evidence.withoutMacroRun().analytics(),
                evidence.withMacroRun().analytics(),
                withoutBenchmark,
                withBenchmark,
                evidence);
    }

    public String renderText(MacroImpactReport report) {
        return """
                Without macro: %d states
                With macro: %d states
                Paths explored: %d
                Convergences: %d
                Bridge usage: %d
                Bridge discovered: %s
                Macro reused: %s
                Improvement: %.2fx
                """.formatted(
                report.withoutMacroStates(),
                report.withMacroStates(),
                report.pathsExplored(),
                report.convergenceCount(),
                report.bridgeUsage(),
                report.bridgeDiscovered() ? "yes" : "no",
                report.macroReused() ? "yes" : "no",
                report.improvementFactor());
    }

    private DiscoveryBenchmarkResult benchmark(
            String id,
            DiscoveryBenchmarkScenario scenario,
            List<String> appliedRuleIds,
            List<String> bridgeRules,
            Set<String> learnedMacros,
            Set<String> reusableMacros,
            Set<DiscoveryExpectation> expectations) {
        if (appliedRuleIds.isEmpty()) {
            return new DiscoveryBenchmarkResult(false, 0, 0, 0, 0, 0, List.of());
        }
        return new DiscoveryBenchmarkRunner().run(new DiscoveryBenchmarkCase(
                id,
                scenario.inputExpression(),
                appliedRuleIds.get(appliedRuleIds.size() - 1),
                List.of(appliedRuleIds),
                expectations,
                bridgeRules,
                learnedMacros,
                reusableMacros));
    }
}
