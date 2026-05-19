package de.regelsuche.input;

public record InputRequest(InputType type, String rawInput) {
    public InputRequest {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (rawInput == null || rawInput.isBlank()) {
            throw new IllegalArgumentException("rawInput must not be blank");
        }
    }
}
