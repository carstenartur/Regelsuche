package de.regelsuche.linalg;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable real vector with the basic linear-algebra operations needed by
 * the killer-demo flow: addition, scalar multiplication and dot product.
 */
public final class Vector {
    private final double[] entries;

    public Vector(double... entries) {
        Objects.requireNonNull(entries, "entries");
        this.entries = entries.clone();
    }

    public int dimension() {
        return entries.length;
    }

    public double get(int index) {
        return entries[index];
    }

    public double[] toArray() {
        return entries.clone();
    }

    public Vector add(Vector other) {
        Objects.requireNonNull(other, "other");
        if (other.dimension() != dimension()) {
            throw new IllegalArgumentException(
                "dimension mismatch: " + dimension() + " vs " + other.dimension());
        }
        double[] result = new double[entries.length];
        for (int i = 0; i < entries.length; i++) {
            result[i] = entries[i] + other.entries[i];
        }
        return new Vector(result);
    }

    public Vector subtract(Vector other) {
        Objects.requireNonNull(other, "other");
        if (other.dimension() != dimension()) {
            throw new IllegalArgumentException(
                "dimension mismatch: " + dimension() + " vs " + other.dimension());
        }
        double[] result = new double[entries.length];
        for (int i = 0; i < entries.length; i++) {
            result[i] = entries[i] - other.entries[i];
        }
        return new Vector(result);
    }

    public Vector scale(double scalar) {
        double[] result = new double[entries.length];
        for (int i = 0; i < entries.length; i++) {
            result[i] = entries[i] * scalar;
        }
        return new Vector(result);
    }

    public double dot(Vector other) {
        Objects.requireNonNull(other, "other");
        if (other.dimension() != dimension()) {
            throw new IllegalArgumentException(
                "dimension mismatch: " + dimension() + " vs " + other.dimension());
        }
        double sum = 0.0;
        for (int i = 0; i < entries.length; i++) {
            sum += entries[i] * other.entries[i];
        }
        return sum;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Vector other && Arrays.equals(this.entries, other.entries);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(entries);
    }

    @Override
    public String toString() {
        return "Vector" + Arrays.toString(entries);
    }
}
