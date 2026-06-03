package de.regelsuche.math.algorithms.equivalence;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** Poly-like structural snapshot used for discovery candidate classification. */
public record PolynomialNormalFormSnapshot(
    int degree,
    List<TermSnapshot> terms,
    List<Monomial> monomials,
    List<Rational> coefficients,
    TermSnapshot leadingTerm,
    Rational content,
    Polynomial primitivePart
) {
    public PolynomialNormalFormSnapshot {
        terms = terms == null ? List.of() : List.copyOf(terms);
        monomials = monomials == null ? List.of() : List.copyOf(monomials);
        coefficients = coefficients == null ? List.of() : List.copyOf(coefficients);
        content = content == null ? Rational.ZERO : content;
        primitivePart = primitivePart == null ? Polynomial.zero() : primitivePart;
    }

    public static PolynomialNormalFormSnapshot from(Polynomial polynomial) {
        if (polynomial == null || polynomial.isZero()) {
            return new PolynomialNormalFormSnapshot(
                0, List.of(), List.of(), List.of(), null, Rational.ZERO, Polynomial.zero());
        }
        MonomialOrder order = new GradedReverseLexOrder();
        List<Map.Entry<Monomial, Rational>> orderedTerms = polynomial.terms().entrySet().stream()
            .sorted((left, right) -> {
                int comparison = order.compare(left.getKey(), right.getKey());
                return comparison != 0 ? comparison : left.getKey().key().compareTo(right.getKey().key());
            })
            .toList();
        List<TermSnapshot> termSnapshots = orderedTerms.stream()
            .map(entry -> new TermSnapshot(entry.getKey(), entry.getValue()))
            .toList();
        List<Rational> orderedCoefficients = termSnapshots.stream().map(TermSnapshot::coefficient).toList();
        Rational polynomialContent = contentOf(orderedCoefficients);
        Polynomial primitive = polynomialContent.isZero()
            ? Polynomial.zero()
            : polynomial.multiply(Rational.ONE.divide(polynomialContent));
        return new PolynomialNormalFormSnapshot(
            polynomial.totalDegree(),
            termSnapshots,
            termSnapshots.stream().map(TermSnapshot::monomial).toList(),
            orderedCoefficients,
            termSnapshots.getFirst(),
            polynomialContent,
            primitive);
    }

    private static Rational contentOf(List<Rational> coefficients) {
        if (coefficients == null || coefficients.isEmpty()) {
            return Rational.ZERO;
        }
        BigInteger gcdNumerator = null;
        BigInteger lcmDenominator = BigInteger.ONE;
        for (Rational coefficient : coefficients) {
            if (coefficient == null || coefficient.isZero()) {
                continue;
            }
            BigInteger numerator = coefficient.numerator().abs();
            gcdNumerator = gcdNumerator == null ? numerator : gcdNumerator.gcd(numerator);
            lcmDenominator = lcm(lcmDenominator, coefficient.denominator().abs());
        }
        if (gcdNumerator == null || gcdNumerator.signum() == 0) {
            return Rational.ZERO;
        }
        return new Rational(gcdNumerator, lcmDenominator);
    }

    private static BigInteger lcm(BigInteger left, BigInteger right) {
        if (left.signum() == 0 || right.signum() == 0) {
            return BigInteger.ZERO;
        }
        BigInteger a = left.abs();
        BigInteger b = right.abs();
        return a.divide(a.gcd(b)).multiply(b);
    }

    public record TermSnapshot(Monomial monomial, Rational coefficient) {
        public TermSnapshot {
            if (monomial == null) {
                throw new IllegalArgumentException("monomial must not be null");
            }
            if (coefficient == null) {
                throw new IllegalArgumentException("coefficient must not be null");
            }
        }
    }
}
