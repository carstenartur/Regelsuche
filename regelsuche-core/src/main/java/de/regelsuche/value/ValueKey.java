package de.regelsuche.value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic structural identity for an {@link ExprValue}.
 *
 * <p>The encoded form is persistence-safe and independent of factory scope. For
 * associative/commutative values, sorting is used only to serialize the unordered
 * multiplicity map; it is not exposed as operand semantics.</p>
 */
public record ValueKey(String encoded) implements Comparable<ValueKey> {
    public ValueKey {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.isEmpty()) {
            throw new IllegalArgumentException("encoded value key must not be empty");
        }
    }

    static ValueKey variable(String name) {
        return new ValueKey("V" + segment(name));
    }

    static ValueKey number(double value) {
        double normalized = value == 0.0d ? 0.0d : value;
        return new ValueKey("N" + Long.toUnsignedString(Double.doubleToLongBits(normalized), 16));
    }

    static ValueKey ordered(ValueOperator operator, List<ExprValue> operands) {
        StringBuilder encoded = new StringBuilder("O").append(segment(operator.id()));
        encoded.append(operands.size()).append(':');
        for (ExprValue operand : operands) {
            encoded.append(segment(operand.key().encoded()));
        }
        return new ValueKey(encoded.toString());
    }

    static ValueKey associativeCommutative(
            ValueOperator operator,
            Map<ExprValue, Integer> multiplicities) {
        List<Map.Entry<ExprValue, Integer>> entries = new ArrayList<>(multiplicities.entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().key()));

        StringBuilder encoded = new StringBuilder("A").append(segment(operator.id()));
        encoded.append(entries.size()).append(':');
        for (Map.Entry<ExprValue, Integer> entry : entries) {
            encoded.append(entry.getValue()).append('*')
                    .append(segment(entry.getKey().key().encoded()));
        }
        return new ValueKey(encoded.toString());
    }

    private static String segment(String text) {
        Objects.requireNonNull(text, "text");
        return text.length() + ":" + text;
    }

    @Override
    public int compareTo(ValueKey other) {
        return encoded.compareTo(other.encoded);
    }

    @Override
    public String toString() {
        return encoded;
    }
}
