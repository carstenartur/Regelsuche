package de.regelsuche.explanation;

/** A single key-value data point within an explanation. */
public record ExplanationFact(String key, String value) {
    public ExplanationFact {
        key = key == null ? "" : key;
        value = value == null ? "" : value;
    }
}
