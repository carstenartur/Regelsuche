package de.regelsuche.polynomial;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable canonical sparse polynomial over an explicit ring. */
public final class SparsePolynomial<C> {
    private final PolynomialRing<C> ring;
    private final NavigableMap<Monomial, C> terms;

    public SparsePolynomial(
        PolynomialRing<C> ring,
        Map<Monomial, C> terms
    ) {
        this.ring = Objects.requireNonNull(ring, "ring");
        Objects.requireNonNull(terms, "terms");
        TreeMap<Monomial, C> normalized = newTermMap(ring);
        for (Map.Entry<Monomial, C> entry : terms.entrySet()) {
            Monomial monomial = Objects.requireNonNull(
                entry.getKey(),
                "monomial");
            if (monomial.arity() != ring.variableCount()) {
                throw new IllegalArgumentException(
                    "monomial arity must equal polynomial ring variable count");
            }
            C coefficient = ring.coefficientDomain().canonical(
                Objects.requireNonNull(
                    entry.getValue(),
                    "coefficient"));
            if (!ring.coefficientDomain().isZero(coefficient)) {
                normalized.put(monomial, coefficient);
            }
        }
        this.terms = Collections.unmodifiableNavigableMap(normalized);
    }

    public static <C> SparsePolynomial<C> zero(
        PolynomialRing<C> ring
    ) {
        return new SparsePolynomial<>(ring, Map.of());
    }

    public static <C> SparsePolynomial<C> one(
        PolynomialRing<C> ring
    ) {
        return constant(ring, ring.coefficientDomain().one());
    }

    public static <C> SparsePolynomial<C> constant(
        PolynomialRing<C> ring,
        C coefficient
    ) {
        Objects.requireNonNull(ring, "ring");
        CoefficientDomain<C> domain = ring.coefficientDomain();
        C canonical = domain.canonical(coefficient);
        return domain.isZero(canonical)
            ? zero(ring)
            : new SparsePolynomial<>(
                ring,
                Map.of(Monomial.one(ring.variableCount()), canonical));
    }

    public PolynomialRing<C> ring() {
        return ring;
    }

    public NavigableMap<Monomial, C> terms() {
        return terms;
    }

    public int termCount() {
        return terms.size();
    }

    public boolean isZero() {
        return terms.isEmpty();
    }

    public boolean isOne() {
        return terms.size() == 1
            && terms.firstKey().totalDegree() == 0
            && ring.coefficientDomain().isOne(
                terms.firstEntry().getValue());
    }

    public boolean isConstant() {
        return totalDegree() <= 0;
    }

    public C leadingCoefficient() {
        if (isZero()) {
            throw new IllegalStateException(
                "zero polynomial has no leading coefficient");
        }
        return terms.firstEntry().getValue();
    }

    public C coefficient(Monomial monomial) {
        Objects.requireNonNull(monomial, "monomial");
        if (monomial.arity() != ring.variableCount()) {
            throw new IllegalArgumentException(
                "coefficient query arity mismatch");
        }
        return terms.getOrDefault(
            monomial,
            ring.coefficientDomain().zero());
    }

    public C coefficient(int... exponents) {
        return coefficient(Monomial.of(exponents));
    }

    public int totalDegree() {
        return isZero()
            ? -1
            : terms.keySet().stream()
                .mapToInt(Monomial::totalDegree)
                .max()
                .orElseThrow();
    }

    public int degree(int variableIndex) {
        if (variableIndex < 0
                || variableIndex >= ring.variableCount()) {
            throw new IllegalArgumentException(
                "variable index outside polynomial ring");
        }
        return isZero()
            ? -1
            : terms.keySet().stream()
                .mapToInt(monomial -> monomial.exponent(variableIndex))
                .max()
                .orElseThrow();
    }

    public int maxCoefficientBitLength() {
        return terms.values().stream()
            .mapToInt(ring.coefficientDomain()::bitLength)
            .max()
            .orElse(0);
    }

    public boolean isHomogeneousOfDegree(int expectedDegree) {
        return expectedDegree >= 0
            && !isZero()
            && terms.keySet().stream().allMatch(
                monomial -> monomial.totalDegree() == expectedDegree);
    }

    public SparsePolynomial<C> add(SparsePolynomial<C> other) {
        requireSameRing(other);
        TreeMap<Monomial, C> result = newTermMap(ring);
        result.putAll(terms);
        other.terms.forEach(
            (monomial, coefficient) -> merge(
                result,
                monomial,
                coefficient));
        return new SparsePolynomial<>(ring, result);
    }

    public SparsePolynomial<C> subtract(
        SparsePolynomial<C> other
    ) {
        return add(other.negate());
    }

    public SparsePolynomial<C> negate() {
        TreeMap<Monomial, C> result = newTermMap(ring);
        terms.forEach((monomial, coefficient) -> result.put(
            monomial,
            ring.coefficientDomain().negate(coefficient)));
        return new SparsePolynomial<>(ring, result);
    }

    public SparsePolynomial<C> scale(C scalar) {
        C checked = ring.coefficientDomain().canonical(scalar);
        if (ring.coefficientDomain().isZero(checked) || isZero()) {
            return zero(ring);
        }
        TreeMap<Monomial, C> result = newTermMap(ring);
        terms.forEach((monomial, coefficient) -> result.put(
            monomial,
            ring.coefficientDomain().multiply(
                coefficient,
                checked)));
        return new SparsePolynomial<>(ring, result);
    }

    public SparsePolynomial<C> multiply(
        SparsePolynomial<C> other
    ) {
        requireSameRing(other);
        if (isZero() || other.isZero()) {
            return zero(ring);
        }
        TreeMap<Monomial, C> result = newTermMap(ring);
        for (Map.Entry<Monomial, C> left : terms.entrySet()) {
            for (Map.Entry<Monomial, C> right
                    : other.terms.entrySet()) {
                merge(
                    result,
                    left.getKey().multiply(right.getKey()),
                    ring.coefficientDomain().multiply(
                        left.getValue(),
                        right.getValue()));
            }
        }
        return new SparsePolynomial<>(ring, result);
    }

    public SparsePolynomial<C> pow(int exponent) {
        if (exponent < 0) {
            throw new IllegalArgumentException(
                "polynomial exponent must not be negative");
        }
        SparsePolynomial<C> result = one(ring);
        SparsePolynomial<C> factor = this;
        int remaining = exponent;
        while (remaining > 0) {
            if ((remaining & 1) == 1) {
                result = result.multiply(factor);
            }
            remaining >>>= 1;
            if (remaining > 0) {
                factor = factor.multiply(factor);
            }
        }
        return result;
    }

    public SparsePolynomial<C> homogenize(
        int targetTotalDegree,
        PolynomialVariable homogenizingVariable
    ) {
        if (targetTotalDegree < 0
                || totalDegree() > targetTotalDegree) {
            throw new IllegalArgumentException(
                "homogenization degree is insufficient");
        }
        PolynomialRing<C> targetRing = ring.appendVariable(
            homogenizingVariable);
        TreeMap<Monomial, C> result = newTermMap(targetRing);
        terms.forEach((monomial, coefficient) -> result.put(
            monomial.appendExponent(
                targetTotalDegree - monomial.totalDegree()),
            coefficient));
        return new SparsePolynomial<>(targetRing, result);
    }

    public String canonicalMaterial() {
        StringBuilder result = new StringBuilder(
            ring.canonicalMaterial());
        for (Map.Entry<Monomial, C> term : terms.entrySet()) {
            append(result, term.getKey().canonicalMaterial());
            append(
                result,
                ring.coefficientDomain().canonicalText(
                    term.getValue()));
        }
        return result.toString();
    }

    private void merge(
        TreeMap<Monomial, C> target,
        Monomial monomial,
        C coefficient
    ) {
        CoefficientDomain<C> domain = ring.coefficientDomain();
        C combined = target.containsKey(monomial)
            ? domain.add(target.get(monomial), coefficient)
            : domain.canonical(coefficient);
        if (domain.isZero(combined)) {
            target.remove(monomial);
        } else {
            target.put(monomial, combined);
        }
    }

    private void requireSameRing(SparsePolynomial<C> other) {
        Objects.requireNonNull(other, "other");
        if (!ring.equals(other.ring)) {
            throw new IllegalArgumentException(
                "polynomial ring mismatch");
        }
    }

    private static <C> TreeMap<Monomial, C> newTermMap(
        PolynomialRing<C> ring
    ) {
        return new TreeMap<>(ring.monomialComparator());
    }

    private static void append(
        StringBuilder target,
        String value
    ) {
        target.append('|')
            .append(value.length())
            .append(':')
            .append(value);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof SparsePolynomial<?> polynomial
                && ring.equals(polynomial.ring)
                && terms.equals(polynomial.terms);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ring, terms);
    }

    @Override
    public String toString() {
        return terms.toString();
    }
}
