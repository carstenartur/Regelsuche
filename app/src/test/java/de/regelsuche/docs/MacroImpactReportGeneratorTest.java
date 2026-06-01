package de.regelsuche.docs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroImpactReportGeneratorTest {
    @Test
    void rendersDiscoveryImpactSummary() {
        MacroImpactReport report = new MacroImpactReportGenerator().generate();
        String text = new MacroImpactReportGenerator().renderText(report);

        assertTrue(report.improvementFactor() > 1.0);
        assertTrue(text.contains("Without macro: 82 states"));
        assertTrue(text.contains("Bridge discovered: yes"));
        assertTrue(text.contains("Macro reused: yes"));
    }
}
