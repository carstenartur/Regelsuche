package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SparsePolynomialTest {
    private final PolynomialRing<BigInteger> ring = new PolynomialRing<>(
        BigIntegerDomain.INSTANCE,
        List.of(
            new PolynomialVariable("x"),
            new PolynomialVariable("y")));

    @Test
    void canonicalSparseArithmeticUsesTheDeclaredRing() {
        SparsePolynomial<BigInteger> x = polynomial(
            Monomial.of(1, 0),
            BigInteger.ONE);
        SparsePolynomial<BigInteger> y = polynomial(
            Monomial.of(0, 1),
            BigInteger.ONE);

        SparsePolynomial<BigInteger> product = x.add(y)
            .multiply(x.subtract(y));

        assertEquals(BigInteger.ONE, product.coefficient(2, 0));
        assertEquals(
            BigInteger.ONE.negate(),
            product.coefficient(0, 2));
        assertEquals(BigInteger.ZERO, product.coefficient(1, 1));
        assertEquals(2, product.totalDegree());
        assertEquals(2, product.termCount());
        assertEquals(product, new SparsePolynomial<>(ring, Map.of(
            Monomial.of(0, 2), BigInteger.ONE.negate(),
            Monomial.of(2, 0), BigInteger.ONE,
            Monomial.of(1, 1), BigInteger.ZERO)));
    }

    @Test
    void polynomialIdentityExcludesDisplayAndSourceOccurrences() {
        PolynomialRing<BigInteger> equivalentRing = new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(
                new PolynomialVariable("x"),
                new PolynomialVariable("y")));
        SparsePolynomial<BigInteger> first = polynomial(
            Monomial.of(1, 0),
            BigInteger.TWO);
        SparsePolynomial<BigInteger> second = new SparsePolynomial<>(
            equivalentRing,
            Map.of(Monomial.of(1, 0), BigInteger.TWO));

        assertEquals(first, second);
        assertEquals(
            first.canonicalMaterial(),
            second.canonicalMaterial());
    }

    @Test
    void homogenizationExtendsTheRingWithoutChangingCoefficients() {
        PolynomialRing<BigInteger> univariateRing = new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")));
        SparsePolynomial<BigInteger> polynomial = new SparsePolynomial<>(
            univariateRing,
            Map.of(
                Monomial.of(3), BigInteger.ONE,
                Monomial.of(0), BigInteger.valueOf(-2)));

        SparsePolynomial<BigInteger> homogenized = polynomial.homogenize(
            4,
            new PolynomialVariable("unit"));

        assertEquals(2, homogenized.ring().variableCount());
        assertEquals(BigInteger.ONE, homogenized.coefficient(3, 1));
        assertEquals(
            BigInteger.valueOf(-2),
            homogenized.coefficient(0, 4));
        assertTrue(homogenized.isHomogeneousOfDegree(4));
    }

    @Test
    void ringMismatchAndInvalidMonomialsFailImmediately() {
        PolynomialRing<BigInteger> otherRing = new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("z")));
        SparsePolynomial<BigInteger> other = new SparsePolynomial<>(
            otherRing,
            Map.of(Monomial.of(1), BigInteger.ONE));

        assertThrows(IllegalArgumentException.class, () ->
            polynomial(Monomial.of(1, 0), BigInteger.ONE).add(other));
        assertThrows(IllegalArgumentException.class, () ->
            new SparsePolynomial<>(ring, Map.of(
                Monomial.of(1), BigInteger.ONE)));
        assertFalse(SparsePolynomial.zero(ring).isOne());
    }

    private SparsePolynomial<BigInteger> polynomial(
        Monomial monomial,
        BigInteger coefficient
    ) {
        return new SparsePolynomial<>(
            ring,
            Map.of(monomial, coefficient));
    }
}
