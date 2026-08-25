package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SparsePolynomialTest {
    private final PolynomialRing<BigInteger> ring = ring(
        PolynomialRing.MonomialOrder.GRADED_LEXICOGRAPHIC,
        "x",
        "y");

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
    void polynomialIdentityExcludesDisplayButIncludesMonomialOrder() {
        PolynomialRing<BigInteger> equivalentRing = ring(
            PolynomialRing.MonomialOrder.GRADED_LEXICOGRAPHIC,
            "x",
            "y");
        PolynomialRing<BigInteger> differentOrder = ring(
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC,
            "x",
            "y");
        SparsePolynomial<BigInteger> first = polynomial(
            Monomial.of(1, 0),
            BigInteger.TWO);
        SparsePolynomial<BigInteger> equivalent = new SparsePolynomial<>(
            equivalentRing,
            Map.of(Monomial.of(1, 0), BigInteger.TWO));
        SparsePolynomial<BigInteger> differentlyOrdered =
            new SparsePolynomial<>(
                differentOrder,
                Map.of(Monomial.of(1, 0), BigInteger.TWO));

        assertEquals(first, equivalent);
        assertEquals(
            first.canonicalMaterial(),
            equivalent.canonicalMaterial());
        assertNotEquals(first, differentlyOrdered);
        assertNotEquals(
            first.canonicalMaterial(),
            differentlyOrdered.canonicalMaterial());
    }

    @Test
    void leadingTermFollowsTheDeclaredOrder() {
        Map<Monomial, BigInteger> terms = Map.of(
            Monomial.of(2, 0, 0), BigInteger.valueOf(2),
            Monomial.of(1, 0, 5), BigInteger.valueOf(3));
        SparsePolynomial<BigInteger> lex = new SparsePolynomial<>(
            ring(
                PolynomialRing.MonomialOrder.LEXICOGRAPHIC,
                "x",
                "y",
                "z"),
            terms);
        SparsePolynomial<BigInteger> gradedLex = new SparsePolynomial<>(
            ring(
                PolynomialRing.MonomialOrder.GRADED_LEXICOGRAPHIC,
                "x",
                "y",
                "z"),
            terms);

        assertEquals(BigInteger.valueOf(2), lex.leadingCoefficient());
        assertEquals(
            BigInteger.valueOf(3),
            gradedLex.leadingCoefficient());
        assertEquals(
            List.of(
                Monomial.of(2, 0),
                Monomial.of(1, 1),
                Monomial.of(0, 2)),
            List.of(
                Monomial.of(2, 0),
                Monomial.of(1, 1),
                Monomial.of(0, 2)).stream()
                .sorted(PolynomialRing.MonomialOrder
                    .GRADED_REVERSE_LEXICOGRAPHIC.comparator())
                .toList());
    }

    @Test
    void homogenizationExtendsTheRingWithoutChangingItsOrder() {
        PolynomialRing<BigInteger> univariateRing = ring(
            PolynomialRing.MonomialOrder.GRADED_REVERSE_LEXICOGRAPHIC,
            "x");
        SparsePolynomial<BigInteger> polynomial = new SparsePolynomial<>(
            univariateRing,
            Map.of(
                Monomial.of(3), BigInteger.ONE,
                Monomial.of(0), BigInteger.valueOf(-2)));

        SparsePolynomial<BigInteger> homogenized = polynomial.homogenize(
            4,
            new PolynomialVariable("unit"));

        assertEquals(2, homogenized.ring().variableCount());
        assertEquals(
            PolynomialRing.MonomialOrder.GRADED_REVERSE_LEXICOGRAPHIC,
            homogenized.ring().monomialOrder());
        assertEquals(BigInteger.ONE, homogenized.coefficient(3, 1));
        assertEquals(
            BigInteger.valueOf(-2),
            homogenized.coefficient(0, 4));
        assertTrue(homogenized.isHomogeneousOfDegree(4));
    }

    @Test
    void ringMismatchAndInvalidMonomialsFailImmediately() {
        PolynomialRing<BigInteger> otherRing = ring(
            PolynomialRing.MonomialOrder.GRADED_LEXICOGRAPHIC,
            "z");
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

    private static PolynomialRing<BigInteger> ring(
        PolynomialRing.MonomialOrder order,
        String... variables
    ) {
        return new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            java.util.Arrays.stream(variables)
                .map(PolynomialVariable::new)
                .toList(),
            order);
    }
}
