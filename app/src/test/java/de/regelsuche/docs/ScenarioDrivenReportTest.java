package de.regelsuche.docs;

import de.regelsuche.benchmark.DiscoveryExpectation;
import de.regelsuche.knowledge.SearchEffect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioDrivenReportTest {
    @Test
    void generatorWorksForLoadedSophieScenarioWithoutCodeChanges() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/sophie-germain.yaml");

        MacroImpactReport report = new MacroImpactReportGenerator().generate(scenario);

        assertEquals(scenario.id(), report.scenarioId());
        assertEquals(scenario.inputExpression(), report.inputExpression());
        assertEquals(scenario.targetExpression(), report.targetExpression());
        assertTrue(report.evidence().success(), report.evidence().failureReason());
        assertTrue(report.evidence().reusedMacros().stream().anyMatch(rule -> rule.startsWith("macro_")));
        long withoutStates = report.evidence().withoutMacroRun().analytics().statesExplored();
        long withStates = report.evidence().withMacroRun().analytics().statesExplored();
        long withoutUnique = report.evidence().withoutMacroRun().analytics().uniqueCanonicalStates();
        long withUnique = report.evidence().withMacroRun().analytics().uniqueCanonicalStates();
        assertEquals(withoutStates + withStates - 1, report.evidence().analytics().statesExplored());
        assertEquals(withoutUnique + withUnique - 1, report.evidence().analytics().uniqueCanonicalStates());
    }

    @Test
    void syntheticScenarioRunsThroughExecutorWithoutReportCodeChanges() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenario(
                "telescoping",
                "Synthetic telescoping",
                "1 / (n * (n + 1))",
                "1 / n - 1 / (n + 1)",
                List.of(DiscoveryExpectation.BRIDGE_REQUIRED),
                List.of("telescoping_fraction"),
                List.of("telescoping"),
                List.of(SearchEffect.BRIDGING),
                List.of("telescoping"),
                List.of("hypothesis_telescoping_fraction"),
                new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
                new DiscoveryBenchmarkScenario.Budgets(3, 40, 5000),
                new DiscoveryBenchmarkScenario.Gallery(false, 1, 3));

        MacroImpactReport report = new MacroImpactReportGenerator().generate(scenario);

        assertEquals("telescoping", report.scenarioId());
        assertTrue(report.evidence().success(), report.evidence().failureReason());
        assertTrue(report.evidence().bridgeRulesUsed().contains("hypothesis_telescoping_fraction"));
    }
}
