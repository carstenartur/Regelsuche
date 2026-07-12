package de.regelsuche.value;

/** Immutable numeric value. Instances are created through {@link ExprValueFactory}. */
public final class NumberValue extends ExprValue {
    private final double value;

    NumberValue(double value) {
        super(ValueKey.number(normalizeZero(value)));
        this.value = normalizeZero(value);
    }

    public double value() {
        return value;
    }

    private static double normalizeZero(double value) {
        return value == 0.0d ? 0.0d : value;
    }

    @Override
    public String toString() {
        return Double.toString(value);
    }
}
