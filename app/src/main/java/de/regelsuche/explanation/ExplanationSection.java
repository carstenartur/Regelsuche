package de.regelsuche.explanation;

import java.util.List;

/**
 * A named group of facts, metrics, and warnings within an explanation.
 * Sections allow structuring explanations into logical topics.
 */
public record ExplanationSection(
    String title,
    List<ExplanationFact> facts,
    List<ExplanationMetric> metrics,
    List<ExplanationWarning> warnings
) {
    public ExplanationSection {
        title = title == null ? "" : title;
        facts = facts == null ? List.of() : List.copyOf(facts);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
