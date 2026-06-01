package de.regelsuche.docs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroImpactReportGeneratorTest {
    @Test
    void rendersDiscoveryImpactSummaryFromScenario() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/complete-square.yaml");
        MacroImpactReport report = new MacroImpactReportGenerator().generate(scenario);
        String text = new MacroImpactReportGenerator().renderText(report);

        assertTrue(report.improvementFactor() > 1.0);
        assertEquals(scenario.id(), report.scenarioId());
        assertEquals(scenario.displayName(), report.caseName());
        assertEquals(scenario.inputExpression(), report.inputExpression());
        assertEquals(scenario.targetExpression(), report.targetExpression());
        assertEquals(report.inputExpression(), report.withoutMacroPath().get(0));
        assertEquals(report.targetExpression(), report.withoutMacroPath().get(report.withoutMacroPath().size() - 1));
        assertEquals(report.targetExpression(), report.withMacroPath().get(report.withMacroPath().size() - 1));
        assertTrue(report.withoutMacroBenchmark().bridgeRules().contains("bridge_complete_square_decomposition"));
        assertEquals(2, report.withMacroPath().size());
        assertEquals(report.withoutMacroPath().size(), report.withoutMacroStates());
        assertEquals(report.withMacroPath().size(), report.withMacroStates());
        assertFalse(report.withoutMacroAnalytics().ruleUsage().keySet().stream().anyMatch(rule -> rule.contains("macro")));
        assertTrue(report.withMacroAnalytics().ruleUsage().keySet().stream().anyMatch(rule -> rule.contains("macro")));
        assertTrue(report.evidence().success(), report.evidence().failureReason());
        assertEquals(scenario.id(), report.evidence().scenarioId());
        assertTrue(text.contains("Without macro: " + report.withoutMacroStates() + " states"));
        assertTrue(text.contains("Bridge discovered: yes"));
        assertTrue(text.contains("Macro reused: yes"));
    }
}
