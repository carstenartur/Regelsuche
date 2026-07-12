package de.regelsuche.value;

import java.util.Objects;

/** Immutable variable value. Instances are created through {@link ExprValueFactory}. */
public final class VariableValue extends ExprValue {
    private final String name;

    VariableValue(String name) {
        super(ValueKey.variable(requireName(name)));
        this.name = requireName(name);
    }

    public String name() {
        return name;
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("variable name must not be blank");
        }
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
