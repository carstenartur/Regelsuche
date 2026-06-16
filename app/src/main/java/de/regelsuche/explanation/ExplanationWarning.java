package de.regelsuche.explanation;

/** A cautionary note attached to an explanation. */
public record ExplanationWarning(String message) {
    public ExplanationWarning {
        message = message == null ? "" : message;
    }
}
