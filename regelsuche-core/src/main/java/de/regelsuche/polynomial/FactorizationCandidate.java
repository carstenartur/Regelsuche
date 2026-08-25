package de.regelsuche.polynomial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Canonical exact decomposition candidate.
 *
 * <p>The unresolved remainder is one for a complete candidate. Product
 * reconstruction and irreducibility/completeness are deliberately separate
 * claims.</p>
 */
public record FactorizationCandidate<C>(
    C unit,
    List<PolynomialFactor<C>> factors,
    SparsePolynomial<C> unresolvedRemainder,
    FactorizationCompleteness completeness,
    String certificateHash
) {
    public FactorizationCandidate {
        Objects.requireNonNull(unresolvedRemainder, "unresolvedRemainder");
        Objects.requireNonNull(completeness, "completeness");
        PolynomialRing<C> ring = unresolvedRemainder.ring();
        unit = ring.coefficientDomain().canonical(
            Objects.requireNonNull(unit, "unit"));
        if (ring.coefficientDomain().isZero(unit)
                || unresolvedRemainder.isZero()
                || certificateHash == null
                || !certificateHash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "factorization candidate is invalid");
        }
        factors = canonicalFactors(
            ring,
            Objects.requireNonNull(factors, "factors"));
        if (factors.isEmpty()) {
            throw new IllegalArgumentException(
                "factorization candidate requires at least one factor");
        }
        if (completeness != FactorizationCompleteness.DECOMPOSITION_ONLY
                && !unresolvedRemainder.isOne()) {
            throw new IllegalArgumentException(
                "complete factorization evidence cannot retain an unresolved remainder");
        }
    }

    private static <C> List<PolynomialFactor<C>> canonicalFactors(
        PolynomialRing<C> ring,
        List<PolynomialFactor<C>> factors
    ) {
        List<PolynomialFactor<C>> ordered = new ArrayList<>(
            List.copyOf(factors));
        if (ordered.stream().anyMatch(factor ->
                factor == null
                    || !ring.equals(factor.polynomial().ring()))) {
            throw new IllegalArgumentException(
                "factorization candidate ring mismatch");
        }
        ordered.sort(Comparator
            .comparing((PolynomialFactor<C> factor) ->
                factor.polynomial().canonicalMaterial())
            .thenComparingInt(PolynomialFactor::multiplicity));
        List<PolynomialFactor<C>> merged = new ArrayList<>();
        for (PolynomialFactor<C> factor : ordered) {
            if (!merged.isEmpty()
                    && merged.getLast().polynomial().equals(
                        factor.polynomial())) {
                PolynomialFactor<C> previous = merged.removeLast();
                merged.add(new PolynomialFactor<>(
                    factor.polynomial(),
                    Math.addExact(
                        previous.multiplicity(),
                        factor.multiplicity())));
            } else {
                merged.add(factor);
            }
        }
        return List.copyOf(merged);
    }

    public String canonicalMaterial() {
        StringBuilder result = new StringBuilder();
        append(
            result,
            unresolvedRemainder.ring()
                .coefficientDomain()
                .canonicalText(unit));
        for (PolynomialFactor<C> factor : factors) {
            append(result, Integer.toString(factor.multiplicity()));
            append(result, factor.polynomial().canonicalMaterial());
        }
        append(result, unresolvedRemainder.canonicalMaterial());
        append(result, completeness.name());
        return result.toString();
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
}
