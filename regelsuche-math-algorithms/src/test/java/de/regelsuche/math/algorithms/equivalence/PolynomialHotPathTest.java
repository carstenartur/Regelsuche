package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialHotPathTest {
    @Test
    void leadingTermSelectionPreservesLexAndGradedReverseLexSemantics() {
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");
        Polynomial polynomial = x.add(y.pow(2));

        assertEquals(Monomial.variable("x"), polynomial.leadingTerm(new LexOrder()).orElseThrow().monomial());
        assertEquals(y.pow(2).leadingTerm(new GradedReverseLexOrder()).orElseThrow().monomial(),
            polynomial.leadingTerm(new GradedReverseLexOrder()).orElseThrow().monomial());
    }

    @Test
    void directSubtractionAndMonomialMultiplicationPreserveExactArithmetic() {
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");
        Polynomial expression = x.add(y).subtract(y.add(Polynomial.constant(Rational.ONE)));

        assertEquals("x - 1", expression.toCanonicalString(new LexOrder()));

        Polynomial scaled = x.multiply(Rational.of(2)).add(Polynomial.constant(Rational.of(3)))
            .multiply(new Monomial(Map.of("y", 2)), Rational.of(-2));
        assertEquals("-4*x*y^2 - 6*y^2", scaled.toCanonicalString(new LexOrder()));
    }

    @Test
    void exponentiationBySquaringPreservesBinomialExpansion() {
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");

        assertEquals(
            "x^5 + 5*x^4*y + 10*x^3*y^2 + 10*x^2*y^3 + 5*x*y^4 + y^5",
            x.add(y).pow(5).toCanonicalString(new GradedReverseLexOrder())
        );
        assertEquals("1", x.add(y).pow(0).toCanonicalString());
    }

    @Test
    void identityFastPathsAndTermViewRemainImmutable() {
        Polynomial x = Polynomial.variable("x");

        assertSame(x, x.add(Polynomial.zero()));
        assertSame(x, x.subtract(Polynomial.zero()));
        assertSame(x, x.multiply(Rational.ONE));
        assertTrue(x.subtract(x).isZero());
        assertThrows(UnsupportedOperationException.class,
            () -> x.terms().put(Monomial.constant(), Rational.ONE));
    }

    @Test
    void monomialOperationsRetainCanonicalOrderingAndDivisibility() {
        Monomial monomial = new Monomial(Map.of("z", 1, "x", 2, "y", 3));
        Monomial divisor = new Monomial(Map.of("x", 1, "y", 2));

        assertEquals("x^2*y^3*z", monomial.key());
        assertEquals(6, monomial.totalDegree());
        assertTrue(divisor.divides(monomial));
        assertEquals("x*y*z", monomial.divideBy(divisor).key());
        assertTrue(Monomial.variable("a").isRelativelyPrimeTo(Monomial.variable("b")));
    }
}
