package de.regelsuche.docs;

import de.regelsuche.canonical.ExpressionCanonicalizer;
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

        assertTrue(report.improvementFactor() > 0.0d);
        assertEquals(scenario.id(), report.scenarioId());
        assertEquals(scenario.displayName(), report.caseName());
        assertEquals(scenario.inputExpression(), report.inputExpression());
        assertEquals(scenario.targetExpression(), report.targetExpression());
        assertEquals(report.inputExpression(), report.withoutMacroPath().get(0));
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        assertEquals(canonicalizer.canonicalize(report.targetExpression()),
                canonicalizer.canonicalize(report.withoutMacroPath().get(report.withoutMacroPath().size() - 1)));
        assertTrue(report.withoutMacroBenchmark().bridgeRules().contains("complete_square_bridge"));
        assertFalse(report.withMacroPath().isEmpty());
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
