package de.regelsuche.docs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MacroImpactReportGeneratorTest {
    @Test
    void rendersDiscoveryImpactSummary() {
        MacroImpactReport report = new MacroImpactReportGenerator().generate();
        String text = new MacroImpactReportGenerator().renderText(report);

        assertThat(report.improvementFactor()).isGreaterThan(1.0);
        assertThat(text).contains("Without macro: 82 states", "Bridge discovered: yes", "Macro reused: yes");
    }
}
