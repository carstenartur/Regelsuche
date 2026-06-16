package de.regelsuche.explanation;

/** A named numeric measurement within an explanation. */
public record ExplanationMetric(String name, long count) {
    public ExplanationMetric {
        name = name == null ? "" : name;
    }
}
