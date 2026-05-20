package de.regelsuche.mining;

import java.util.List;
import java.util.Objects;

public record PatternFunction(String name, List<RulePatternNode> arguments) implements RulePatternNode {
    public PatternFunction {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("function name must not be blank");
        }
        Objects.requireNonNull(arguments, "arguments");
        arguments = List.copyOf(arguments);
    }
}
