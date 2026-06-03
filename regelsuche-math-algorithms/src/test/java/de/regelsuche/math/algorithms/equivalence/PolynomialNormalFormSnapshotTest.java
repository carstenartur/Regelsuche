package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PolynomialNormalFormSnapshotTest {
    private final PolynomialArithmetic arithmetic = new PolynomialArithmetic();

    @Test
    void capturesQuadraticDegreeTermsLeadingTermAndContent() {
        Polynomial polynomial = arithmetic.parse("x^2 + 2*x + 1").orElseThrow();

        PolynomialNormalFormSnapshot snapshot = PolynomialNormalFormSnapshot.from(polynomial);

        assertEquals(2, snapshot.degree());
        assertEquals(3, snapshot.terms().size());
        assertEquals("x^2", snapshot.leadingTerm().monomial().key());
        assertEquals(new Rational(java.math.BigInteger.ONE, java.math.BigInteger.ONE), snapshot.content());
        assertEquals("x^2 + 2*x + 1", snapshot.primitivePart().toCanonicalString());
    }

    @Test
    void preservesSophieGermainInputShapeAndContentForUnitCoefficients() {
        Polynomial polynomial = arithmetic.parse("x^4 + 4*y^4").orElseThrow();

        PolynomialNormalFormSnapshot snapshot = PolynomialNormalFormSnapshot.from(polynomial);

        assertEquals(4, snapshot.degree());
        assertEquals(2, snapshot.terms().size());
        assertEquals("x^4", snapshot.leadingTerm().monomial().key());
        assertEquals("1", snapshot.content().toString());
        assertEquals("x^4 + 4*y^4", snapshot.primitivePart().toCanonicalString());
    }

    @Test
    void factorsOutNumericContentIntoPrimitivePart() {
        Polynomial polynomial = arithmetic.parse("2*x^2 + 4*x").orElseThrow();

        PolynomialNormalFormSnapshot snapshot = PolynomialNormalFormSnapshot.from(polynomial);

        assertEquals("2", snapshot.content().toString());
        assertEquals("x^2 + 2*x", snapshot.primitivePart().toCanonicalString());
    }

    @Test
    void exposesMonomialsAndCoefficientsForMultivariatePolynomial() {
        Polynomial polynomial = arithmetic.parse("x^2*y + x*y^2").orElseThrow();

        PolynomialNormalFormSnapshot snapshot = PolynomialNormalFormSnapshot.from(polynomial);

        assertEquals(2, snapshot.monomials().size());
        assertEquals(2, snapshot.coefficients().size());
        assertTrue(snapshot.monomials().stream().anyMatch(monomial -> monomial.key().equals("x^2*y")));
        assertTrue(snapshot.monomials().stream().anyMatch(monomial -> monomial.key().equals("x*y^2")));
        assertTrue(snapshot.coefficients().stream().allMatch(Rational::isOne));
        assertNotNull(snapshot.leadingTerm());
    }
}
