package de.regelsuche.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.knowledge.RuleStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleQualityDashboardTest {
    @Test
    void markdownUsesResultColumnForPassFailValues() {
        RuleValidationReport report = new RuleValidationReport(
                "rule-1",
                RuleStatus.VALIDATED.name(),
                2,
                2,
                List.of(),
                List.of(),
                List.of());

        String markdown = new RuleQualityDashboard().renderMarkdown(List.of(report));

        assertTrue(markdown.contains("| Rule | Result | Examples | Counterexamples | Pass rate |"));
        assertTrue(markdown.contains("| rule-1 | PASS | 2/2 | 0 | 100% |"));
    }
}
