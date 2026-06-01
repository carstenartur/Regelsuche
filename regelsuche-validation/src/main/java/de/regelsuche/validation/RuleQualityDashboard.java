package de.regelsuche.validation;

import java.util.List;
import java.util.Locale;

public final class RuleQualityDashboard {
    public String renderMarkdown(List<RuleValidationReport> reports) {
        StringBuilder markdown = new StringBuilder("| Rule | Result | Examples | Counterexamples | Pass rate |\n");
        markdown.append("| --- | --- | ---: | ---: | ---: |\n");
        for (RuleValidationReport report : reports) {
            markdown.append("| ").append(report.ruleId())
                    .append(" | ").append(report.passed() ? "PASS" : "FAIL")
                    .append(" | ").append(report.examplesPassed()).append('/').append(report.examplesTotal())
                    .append(" | ").append(report.counterexamples().size())
                    .append(" | ").append(String.format(Locale.ROOT, "%.0f%%", report.passRate() * 100.0d))
                    .append(" |\n");
        }
        return markdown.toString();
    }
}
