package de.regelsuche.knowledge;

public record ValidationExample(String from, String to) {
    public ValidationExample {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("validation example from is required");
        }
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("validation example to is required");
        }
    }
}
