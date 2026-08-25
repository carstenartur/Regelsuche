package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnivariatePolynomialAlgorithmsTest {
    private final PolynomialRing<ExactRational> rationalRing =
        new PolynomialRing<>(
            ExactRationalField.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);

    @Test
    void computesMonicEuclideanGcdWithDeterministicEvidence() {
        SparsePolynomial<ExactRational> xMinusOne = polynomial(-1, 1);
        SparsePolynomial<ExactRational> xPlusTwo = polynomial(2, 1);
        SparsePolynomial<ExactRational> left =
            xMinusOne.pow(3).multiply(xPlusTwo.pow(2));
        SparsePolynomial<ExactRational> right =
            xMinusOne.pow(2).multiply(xPlusTwo);

        UnivariatePolynomialAlgorithms.GcdResult<ExactRational> first =
            UnivariatePolynomialAlgorithms.gcd(
                left,
                right,
                20_000);
        UnivariatePolynomialAlgorithms.GcdResult<ExactRational> second =
            UnivariatePolynomialAlgorithms.gcd(
                left,
                right,
                20_000);

        assertTrue(first.completed(), first.toString());
        assertEquals(right, first.gcd());
        assertEquals(first, second);
        assertTrue(first.work().totalWorkUnits() > 0);
        assertTrue(first.certificateHash().matches(
            "sha256:[0-9a-f]{64}"));
    }

    @Test
    void zeroZeroGcdRemainsAnExplicitUndefinedCase() {
        SparsePolynomial<ExactRational> zero =
            SparsePolynomial.zero(rationalRing);

        UnivariatePolynomialAlgorithms.GcdResult<ExactRational> result =
            UnivariatePolynomialAlgorithms.gcd(zero, zero, 100);

        assertFalse(result.completed());
        assertEquals(
            UnivariatePolynomialAlgorithms.Status.UNSUPPORTED_SHAPE,
            result.status());
        assertEquals("GCD_ZERO_ZERO_UNDEFINED", result.detailCode());
        assertEquals(0, result.work().totalWorkUnits());
    }

    @Test
    void budgetAndNonfieldDomainsRemainExplicitNonresults() {
        SparsePolynomial<ExactRational> left =
            polynomial(-1, 1).pow(4);
        SparsePolynomial<ExactRational> right =
            polynomial(-1, 1).pow(3);
        UnivariatePolynomialAlgorithms.GcdResult<ExactRational> limited =
            UnivariatePolynomialAlgorithms.gcd(left, right, 1);

        PolynomialRing<java.math.BigInteger> integerRing =
            new PolynomialRing<>(
                BigIntegerDomain.INSTANCE,
                List.of(new PolynomialVariable("x")),
                PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        SparsePolynomial<java.math.BigInteger> integerPolynomial =
            new SparsePolynomial<>(
                integerRing,
                java.util.Map.of(
                    Monomial.of(1), java.math.BigInteger.ONE,
                    Monomial.of(0), java.math.BigInteger.ONE));
        UnivariatePolynomialAlgorithms.GcdResult<java.math.BigInteger>
            unsupported = UnivariatePolynomialAlgorithms.gcd(
                integerPolynomial,
                integerPolynomial,
                100);

        assertFalse(limited.completed());
        assertEquals(
            UnivariatePolynomialAlgorithms.Status.BUDGET_INCONCLUSIVE,
            limited.status());
        assertEquals(
            UnivariatePolynomialAlgorithms.Status.UNSUPPORTED_DOMAIN,
            unsupported.status());
    }

    private SparsePolynomial<ExactRational> polynomial(
        long constant,
        long linear
    ) {
        return UnivariatePolynomialView.of(
            rationalRing,
            List.of(
                ExactRational.integer(constant),
                ExactRational.integer(linear)))
            .toSparsePolynomial();
    }
}
