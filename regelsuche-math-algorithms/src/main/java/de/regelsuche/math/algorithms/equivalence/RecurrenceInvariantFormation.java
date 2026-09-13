package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.scalar.ExactRational;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/** Frozen, target-free inputs for one bounded rational homogeneous recurrence-invariant search. */
public record RecurrenceInvariantFormation(Recurrence recurrence, List<Integer> shifts, int degree,
    List<BasisTerm> basis, List<ExactRational> lambdas, List<String> assumptions, Bounds bounds) {
    public static final String SCHEMA = "regelsuche.recurrence-invariant-formation/v1";
    public static final String FRAGMENT = "rational-homogeneous-companion-polynomial-invariant/v1";
    public static final String CHARTS = "first-nonzero-coefficient-one-preceding-coefficients-zero/v1";

    public RecurrenceInvariantFormation {
        Objects.requireNonNull(recurrence, "recurrence");
        Objects.requireNonNull(bounds, "bounds");
        recurrence.coefficients().forEach(value -> requireBits(value, bounds.maxScalarBits()));
        recurrence.initialValues().forEach(value -> requireBits(value, bounds.maxScalarBits()));
        requireSize(shifts, recurrence.order(), recurrence.order(), "shifts");
        shifts = List.copyOf(Objects.requireNonNull(shifts, "shifts"));
        if (!shifts.equals(IntStream.range(0, recurrence.order()).boxed().toList()) || degree < 1 || degree > 3) {
            throw new IllegalArgumentException("only consecutive state shifts and homogeneous degree one through three are supported");
        }
        requireSize(basis, 1, 12, "basis");
        basis = List.copyOf(Objects.requireNonNull(basis, "basis"));
        if (basis.isEmpty() || basis.size() > 12 || new HashSet<>(basis).size() != basis.size()) {
            throw new IllegalArgumentException("basis must be nonempty, unique and bounded by twelve terms");
        }
        for (BasisTerm term : basis) {
            if (term.exponents().size() != recurrence.order() || term.exponents().stream().mapToInt(Integer::intValue).sum() != degree) {
                throw new IllegalArgumentException("basis term does not belong to the frozen state and degree");
            }
        }
        basis = basis.stream().sorted(RecurrenceInvariantFormation::compareTerms).toList();
        requireSize(lambdas, 1, 16, "lambdas");
        lambdas = List.copyOf(Objects.requireNonNull(lambdas, "lambdas"));
        // Exact comparison can multiply big integers: admit scalar sizes before hashing or sorting them.
        lambdas.forEach(value -> requireBits(value, bounds.maxCoefficientBits()));
        if (lambdas.isEmpty() || lambdas.size() > 16 || new HashSet<>(lambdas).size() != lambdas.size()) {
            throw new IllegalArgumentException("lambda domain must be finite, nonempty and unique");
        }
        lambdas = lambdas.stream().sorted().toList();
        requireSize(assumptions, 0, 16, "assumptions");
        assumptions = List.copyOf(Objects.requireNonNull(assumptions, "assumptions"));
        if (assumptions.size() > 16 || assumptions.stream().anyMatch(value -> value.isBlank() || value.length() > 1_024
                || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("assumption context exceeds the bounded input contract");
        }
        // Even unsupported contexts are frozen: UTF-8 replacement must not alias a different assumption.
        for (String assumption : assumptions) {
            for (int index = 0; index < assumption.length(); index++) {
                char current = assumption.charAt(index);
                if (Character.isHighSurrogate(current)) {
                    if (++index >= assumption.length() || !Character.isLowSurrogate(assumption.charAt(index))) {
                        throw new IllegalArgumentException("assumption context contains malformed Unicode");
                    }
                } else if (Character.isLowSurrogate(current)) {
                    throw new IllegalArgumentException("assumption context contains malformed Unicode");
                }
            }
        }
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject().property("schema", SCHEMA).property("fragment", FRAGMENT).property("charts", CHARTS)
            .property("solverId", ExactLinearPolynomialHoleSolver.SOLVER_ID).property("solverRevision", ExactLinearPolynomialHoleSolver.REVISION)
            .stringArray("recurrenceCoefficients", text(recurrence.coefficients())).stringArray("initialValues", text(recurrence.initialValues()))
            .array("shifts", array -> shifts.forEach(array::value)).property("degree", degree)
            .array("basis", array -> basis.forEach(term -> array.arrayValue(values -> term.exponents().forEach(values::value))))
            .stringArray("lambdas", text(lambdas)).stringArray("assumptions", assumptions)
            .object("bounds", j -> j.property("maxCoefficientBits", bounds.maxCoefficientBits())
                .property("maxScalarBits", bounds.maxScalarBits()).property("maxAttemptWorkUnits", bounds.maxAttemptWorkUnits())
                .property("maxTotalWorkUnits", bounds.maxTotalWorkUnits()))
            .endObject().toString();
    }

    public String contentHash() { return hash(toCanonicalJson()); }
    public List<String> holeIds() { return IntStream.range(0, basis.size()).mapToObj(i -> "coefficient" + i).toList(); }

    /** a_(n+k) = sum coefficients[i] * a_(n+i), with exactly k supplied initial values. */
    public record Recurrence(List<ExactRational> coefficients, List<ExactRational> initialValues) {
        public Recurrence {
            requireSize(coefficients, 1, 3, "coefficients");
            requireSize(initialValues, coefficients.size(), coefficients.size(), "initialValues");
            coefficients = List.copyOf(Objects.requireNonNull(coefficients, "coefficients"));
            initialValues = List.copyOf(Objects.requireNonNull(initialValues, "initialValues"));
            if (coefficients.isEmpty() || coefficients.size() > 3 || coefficients.size() != initialValues.size()) {
                throw new IllegalArgumentException("recurrence order must be one through three with exactly that many initial values");
            }
        }
        public int order() { return coefficients.size(); }
    }

    public record BasisTerm(List<Integer> exponents) {
        public BasisTerm {
            requireSize(exponents, 1, 3, "exponents");
            exponents = List.copyOf(Objects.requireNonNull(exponents, "exponents"));
            if (exponents.isEmpty() || exponents.size() > 3 || exponents.stream().anyMatch(value -> value < 0 || value > 3)) {
                throw new IllegalArgumentException("basis exponents exceed the bounded polynomial fragment");
            }
        }
    }

    public record Bounds(int maxCoefficientBits, int maxScalarBits, int maxAttemptWorkUnits, int maxTotalWorkUnits) {
        public Bounds {
            if (maxCoefficientBits < 1 || maxScalarBits < maxCoefficientBits || maxScalarBits > 4_096
                    || maxAttemptWorkUnits < 0 || maxAttemptWorkUnits > 10_000_000
                    || maxTotalWorkUnits < 0 || maxTotalWorkUnits > 10_000_000) {
                throw new IllegalArgumentException("invalid coefficient, scalar or work bounds");
            }
        }
    }

    /** Finite basis generation happens before discovery and inspects neither solutions nor historical names. */
    public static List<BasisTerm> homogeneousBasis(int order, int degree) {
        if (order < 1 || order > 3 || degree < 1 || degree > 3) {
            throw new IllegalArgumentException("basis generator supports order and degree one through three");
        }
        List<BasisTerm> result = new ArrayList<>();
        enumerate(order, degree, new ArrayList<>(), result);
        return List.copyOf(result);
    }

    private static void enumerate(int remainingVariables, int remainingDegree, List<Integer> prefix, List<BasisTerm> result) {
        if (remainingVariables == 1) {
            var values = new ArrayList<>(prefix);
            values.add(remainingDegree);
            result.add(new BasisTerm(values));
            return;
        }
        for (int exponent = remainingDegree; exponent >= 0; exponent--) {
            prefix.add(exponent);
            enumerate(remainingVariables - 1, remainingDegree - exponent, prefix, result);
            prefix.removeLast();
        }
    }

    private static int compareTerms(BasisTerm left, BasisTerm right) {
        for (int i = 0; i < left.exponents().size(); i++) {
            int result = Integer.compare(right.exponents().get(i), left.exponents().get(i));
            if (result != 0) { return result; }
        }
        return 0;
    }

    static void requireBits(ExactRational value, int limit) {
        if (Math.max(value.numerator().abs().bitLength(), value.denominator().bitLength()) > limit) {
            throw new IllegalArgumentException("rational value exceeds the frozen scalar bit bound");
        }
    }
    private static void requireSize(List<?> values, int minimum, int maximum, String field) {
        Objects.requireNonNull(values, field);
        if (values.size() < minimum || values.size() > maximum) { throw new IllegalArgumentException(field + " exceeds dimension bound"); }
    }
    static List<String> text(List<ExactRational> values) { return values.stream().map(ExactRational::canonicalText).toList(); }
    static String hash(String value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
