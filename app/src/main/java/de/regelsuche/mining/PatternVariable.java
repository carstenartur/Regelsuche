package de.regelsuche.mining;

public record PatternVariable(String name) implements RulePatternNode {
    public PatternVariable {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
