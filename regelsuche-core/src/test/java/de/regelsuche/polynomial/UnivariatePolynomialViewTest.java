package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnivariatePolynomialViewTest {
    private final PolynomialRing<ExactRational> ring =
        new PolynomialRing<>(
            ExactRationalField.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);

    @Test
    void exactRationalFieldDeclaresCharacteristicAndIntegerEmbedding() {
        assertEquals(
            BigInteger.ZERO,
            ExactRationalField.INSTANCE.characteristic());
        assertEquals(
            ExactRational.integer(
                new BigInteger("9007199254740993")),
            ExactRationalField.INSTANCE.fromInteger(
                new BigInteger("9007199254740993")));
    }

    @Test
    void projectionRoundTripsAndRetainsCanonicalAscendingCoefficients() {
        UnivariatePolynomialView<ExactRational> view =
            UnivariatePolynomialView.of(
                ring,
                List.of(q(2), q(0), q(-3), q(1), q(0)));

        assertEquals(3, view.degree());
        assertEquals(q(2), view.coefficient(0));
        assertEquals(q(-3), view.coefficient(2));
        assertEquals(q(1), view.leadingCoefficient());
        assertEquals(
            view,
            UnivariatePolynomialView.from(
                view.toSparsePolynomial()));
    }

    @Test
    void derivativeAndExactLongDivisionUseTheDeclaredField() {
        UnivariatePolynomialView<ExactRational> cubic =
            UnivariatePolynomialView.of(
                ring,
                List.of(q(-1), q(0), q(0), q(1)));
        UnivariatePolynomialView<ExactRational> linear =
            UnivariatePolynomialView.of(
                ring,
                List.of(q(-1), q(1)));

        assertEquals(
            List.of(q(0), q(0), q(3)),
            cubic.derivative().coefficients());
        assertEquals(
            List.of(q(1), q(1), q(1)),
            cubic.exactQuotient(
                linear,
                ExactRationalField.INSTANCE).coefficients());
        assertTrue(
            cubic.divideAndRemainder(
                linear,
                ExactRationalField.INSTANCE)
                .remainder()
                .isZero());
    }

    @Test
    void nonexactDivisionAndMultivariateProjectionFailClosed() {
        UnivariatePolynomialView<ExactRational> dividend =
            UnivariatePolynomialView.of(
                ring,
                List.of(q(1), q(0), q(1)));
        UnivariatePolynomialView<ExactRational> divisor =
            UnivariatePolynomialView.of(
                ring,
                List.of(q(1), q(1)));
        PolynomialRing<ExactRational> multivariate =
            new PolynomialRing<>(
                ExactRationalField.INSTANCE,
                List.of(
                    new PolynomialVariable("x"),
                    new PolynomialVariable("y")),
                PolynomialRing.MonomialOrder.LEXICOGRAPHIC);

        assertThrows(ArithmeticException.class, () ->
            dividend.exactQuotient(
                divisor,
                ExactRationalField.INSTANCE));
        assertThrows(IllegalArgumentException.class, () ->
            UnivariatePolynomialView.from(
                SparsePolynomial.one(multivariate)));
    }

    private static ExactRational q(long value) {
        return ExactRational.integer(value);
    }
}
