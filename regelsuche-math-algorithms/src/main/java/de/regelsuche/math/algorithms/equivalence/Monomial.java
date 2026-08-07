package de.regelsuche.math.algorithms.equivalence;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public record Monomial(Map<String, Integer> powers) {
    public Monomial {
        TreeMap<String, Integer> normalized = new TreeMap<>();
        if (powers != null) {
            for (Map.Entry<String, Integer> entry : powers.entrySet()) {
                String variable = entry.getKey();
                Integer exponent = entry.getValue();
                if (variable == null || variable.isBlank()) {
                    throw new IllegalArgumentException("variable must not be blank");
                }
                if (exponent == null || exponent < 0) {
                    throw new IllegalArgumentException("exponent must be non-negative");
                }
                if (exponent > 0) {
                    normalized.put(variable, exponent);
                }
            }
        }
        powers = Collections.unmodifiableNavigableMap(normalized);
    }

    public static Monomial constant() {
        return new Monomial(Map.of());
    }

    public static Monomial variable(String variable) {
        return new Monomial(Map.of(variable, 1));
    }

    public Monomial multiply(Monomial other) {
        if (powers.isEmpty()) {
            return other;
        }
        if (other.powers.isEmpty()) {
            return this;
        }
        Map<String, Integer> merged = new HashMap<>(powers);
        for (Map.Entry<String, Integer> entry : other.powers.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        return new Monomial(merged);
    }

    public boolean divides(Monomial other) {
        if (powers.size() > other.powers.size()) {
            return false;
        }
        for (Map.Entry<String, Integer> entry : powers.entrySet()) {
            if (other.exponentOf(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    public boolean isRelativelyPrimeTo(Monomial other) {
        Map<String, Integer> smaller = powers.size() <= other.powers.size() ? powers : other.powers;
        Map<String, Integer> larger = smaller == powers ? other.powers : powers;
        for (String variable : smaller.keySet()) {
            if (larger.containsKey(variable)) {
                return false;
            }
        }
        return true;
    }

    public Monomial divideBy(Monomial divisor) {
        if (!divisor.divides(this)) {
            throw new ArithmeticException("monomial is not divisible by divisor");
        }
        if (divisor.powers.isEmpty()) {
            return this;
        }
        Map<String, Integer> quotient = new HashMap<>(powers);
        for (Map.Entry<String, Integer> entry : divisor.powers.entrySet()) {
            int exponent = quotient.get(entry.getKey()) - entry.getValue();
            if (exponent == 0) {
                quotient.remove(entry.getKey());
            } else {
                quotient.put(entry.getKey(), exponent);
            }
        }
        return new Monomial(quotient);
    }

    public Monomial lcm(Monomial other) {
        if (powers.isEmpty()) {
            return other;
        }
        if (other.powers.isEmpty()) {
            return this;
        }
        Map<String, Integer> merged = new HashMap<>(powers);
        for (Map.Entry<String, Integer> entry : other.powers.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), Math::max);
        }
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
        int degree = 0;
        for (int exponent : powers.values()) {
            degree += exponent;
        }
        return degree;
    }

    public String key() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Integer> entry : powers.entrySet()) {
            if (builder.length() > 0) {
                builder.append('*');
            }
            builder.append(entry.getKey());
            if (entry.getValue() != 1) {
                builder.append('^').append(entry.getValue());
            }
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    NavigableMap<String, Integer> orderedPowers() {
        return (NavigableMap<String, Integer>) powers;
    }
}
