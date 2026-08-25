package de.regelsuche.polynomial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Canonical exponent vector in one declared polynomial ring. */
public record Monomial(List<Integer> exponents)
        implements Comparable<Monomial> {
    public Monomial {
        exponents = List.copyOf(
            Objects.requireNonNull(exponents, "exponents"));
        if (exponents.stream().anyMatch(
                value -> value == null || value < 0)) {
            throw new IllegalArgumentException(
                "monomial exponents must be nonnegative");
        }
    }

    public static Monomial of(int... exponents) {
        Objects.requireNonNull(exponents, "exponents");
        return new Monomial(
            Arrays.stream(exponents).boxed().toList());
    }

    public static Monomial one(int arity) {
        if (arity < 0) {
            throw new IllegalArgumentException(
                "monomial arity must not be negative");
        }
        return new Monomial(
            Collections.nCopies(arity, 0));
    }

    public int arity() {
        return exponents.size();
    }

    public int exponent(int index) {
        return exponents.get(index);
    }

    public int totalDegree() {
        int result = 0;
        for (int exponent : exponents) {
            result = Math.addExact(result, exponent);
        }
        return result;
    }

    public Monomial multiply(Monomial other) {
        Objects.requireNonNull(other, "other");
        if (arity() != other.arity()) {
            throw new IllegalArgumentException(
                "monomial arity mismatch");
        }
        List<Integer> result = new ArrayList<>(arity());
        for (int index = 0; index < arity(); index++) {
            result.add(Math.addExact(
                exponent(index),
                other.exponent(index)));
        }
        return new Monomial(result);
    }

    public Monomial appendExponent(int exponent) {
        if (exponent < 0) {
            throw new IllegalArgumentException(
                "appended exponent must be nonnegative");
        }
        List<Integer> result = new ArrayList<>(exponents);
        result.add(exponent);
        return new Monomial(result);
    }

    public String canonicalMaterial() {
        return exponents.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(","));
    }

    @Override
    public int compareTo(Monomial other) {
        Objects.requireNonNull(other, "other");
        int degreeComparison = Integer.compare(
            other.totalDegree(),
            totalDegree());
        if (degreeComparison != 0) {
            return degreeComparison;
        }
        int length = Math.max(arity(), other.arity());
        for (int index = 0; index < length; index++) {
            int left = index < arity() ? exponent(index) : 0;
            int right = index < other.arity()
                ? other.exponent(index)
                : 0;
            int comparison = Integer.compare(right, left);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(arity(), other.arity());
    }
}
