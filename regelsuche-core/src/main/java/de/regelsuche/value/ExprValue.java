package de.regelsuche.value;

import java.util.Objects;

/** Immutable mathematical expression value, independent of syntax occurrences. */
public abstract sealed class ExprValue
        permits VariableValue, NumberValue, OrderedValue, AssociativeCommutativeValue {

    private final ValueKey key;

    protected ExprValue(ValueKey key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    public final ValueKey key() {
        return key;
    }

    public final boolean sameValue(ExprValue other) {
        return other != null && key.equals(other.key);
    }

    @Override
    public final boolean equals(Object other) {
        return this == other || other instanceof ExprValue value && key.equals(value.key);
    }

    @Override
    public final int hashCode() {
        return key.hashCode();
    }
}
