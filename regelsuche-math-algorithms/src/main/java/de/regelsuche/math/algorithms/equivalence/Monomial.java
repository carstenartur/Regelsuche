package de.regelsuche.math.algorithms.equivalence;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public record Monomial(Map<String, Integer> powers) {
    public Monomial {
        TreeMap<String, Integer> normalized = new TreeMap<>();
        if (powers != null) {
            powers.forEach((variable, exponent) -> {
                if (variable == null || variable.isBlank()) {
                    throw new IllegalArgumentException("variable must not be blank");
                }
                if (exponent == null || exponent < 0) {
                    throw new IllegalArgumentException("exponent must be non-negative");
                }
                if (exponent > 0) {
                    normalized.put(variable, exponent);
                }
            });
        }
        powers = Map.copyOf(normalized);
    }

    public static Monomial constant() {
        return new Monomial(Map.of());
    }

    public static Monomial variable(String variable) {
        return new Monomial(Map.of(variable, 1));
    }

    public Monomial multiply(Monomial other) {
        Map<String, Integer> merged = new HashMap<>(powers);
        other.powers.forEach((variable, exponent) -> merged.merge(variable, exponent, Integer::sum));
        return new Monomial(merged);
    }

    public boolean divides(Monomial other) {
        return powers.entrySet().stream()
            .allMatch(entry -> other.exponentOf(entry.getKey()) >= entry.getValue());
    }

    public Monomial divideBy(Monomial divisor) {
        if (!divisor.divides(this)) {
            throw new ArithmeticException("monomial is not divisible by divisor");
        }
        Map<String, Integer> quotient = new HashMap<>(powers);
        divisor.powers.forEach((variable, exponent) -> quotient.compute(variable, (ignored, current) -> current - exponent));
        return new Monomial(quotient);
    }

    public Monomial lcm(Monomial other) {
        Map<String, Integer> merged = new HashMap<>(powers);
        other.powers.forEach((variable, exponent) -> merged.merge(variable, exponent, Math::max));
        return new Monomial(merged);
    }

    public int exponentOf(String variable) {
        return powers.getOrDefault(variable, 0);
    }

    public Monomial without(String variable) {
        if (!powers.containsKey(variable)) {
            return this;
        }
        Map<String, Integer> reduced = new HashMap<>(powers);
        reduced.remove(variable);
        return new Monomial(reduced);
    }

    public int totalDegree() {
        return powers.values().stream().mapToInt(Integer::intValue).sum();
    }

    public String key() {
        StringBuilder builder = new StringBuilder();
        new TreeMap<>(powers).forEach((name, exponent) -> {
            if (builder.length() > 0) {
                builder.append('*');
            }
            builder.append(name);
            if (exponent != 1) {
                builder.append('^').append(exponent);
            }
        });
        return builder.toString();
    }
}
