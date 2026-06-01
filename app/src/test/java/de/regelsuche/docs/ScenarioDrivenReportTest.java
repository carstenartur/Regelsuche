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
        assertTrue(report.evidence().reusedMacros().contains("macro_learned_sophie_germain_factorization"));
    }

    @Test
    void syntheticScenarioRunsThroughExecutorWithoutReportCodeChanges() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenario(
                "telescoping",
                "Synthetic telescoping",
                "1 / (n * (n + 1))",
                "1 / n - 1 / (n + 1)",
                List.of(DiscoveryExpectation.BRIDGE_REQUIRED),
                List.of("telescoping"),
                List.of(SearchEffect.BRIDGING),
                List.of("telescoping"),
                List.of("bridge_telescoping_partial_fraction"),
                new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
                new DiscoveryBenchmarkScenario.Budgets(3, 40, 5000),
                new DiscoveryBenchmarkScenario.Gallery(false, 1, 3));

        MacroImpactReport report = new MacroImpactReportGenerator().generate(scenario);

        assertEquals("telescoping", report.scenarioId());
        assertTrue(report.evidence().success(), report.evidence().failureReason());
        assertTrue(report.evidence().bridgeRulesUsed().contains("bridge_telescoping_partial_fraction"));
    }
}
