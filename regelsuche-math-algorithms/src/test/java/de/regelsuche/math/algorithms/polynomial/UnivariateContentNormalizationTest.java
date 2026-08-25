package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UnivariateContentNormalizationTest {
    private final PolynomialRing<BigInteger> integerRing =
        new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    private final PolynomialRing<ExactRational> rationalRing =
        new PolynomialRing<>(
            ExactRationalField.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    private final FactorizationRequest.StructuralLimits limits =
        new FactorizationRequest.StructuralLimits(
            1,
            32,
            64,
            4_096);

    @Test
    void normalizesIntegerContentAndMovesTheLeadingSignIntoTheScalar() {
        SparsePolynomial<BigInteger> source =
            integer(-18, 12, 0, -6);

        UnivariateContentResult result =
            UnivariateContentNormalization.normalizeInteger(
                source,
                request(4_096, 10_000));

        assertTrue(result.completed(), result.toString());
        assertEquals(
            BigInteger.ONE,
            result.denominatorClearingFactor());
        assertEquals(
            BigInteger.valueOf(6),
            result.integerContent());
        assertEquals(q(-6, 1), result.scalar());
        assertEquals(
            integers(3, -2, 0, 1),
            UnivariatePolynomialView.from(
                result.primitivePart()).coefficients());
        assertTrue(result.work().totalWorkUnits() > 0);
        assertTrue(result.certificateHash().matches(
            "sha256:[0-9a-f]{64}"));
    }

    @Test
    void rationalNormalizationIsCanonicalAndDeterministic() {
        SparsePolynomial<ExactRational> source =
            rational(
                q(-2, 5),
                q(2, 3),
                q(-4, 15));
        UnivariateContentRequest request =
            request(4_096, 10_000);

        UnivariateContentResult first =
            UnivariateContentNormalization.normalizeRational(
                source,
                request);
        UnivariateContentResult second =
            UnivariateContentNormalization.normalizeRational(
                source,
                request);

        assertTrue(first.completed(), first.toString());
        assertEquals(
            ExactRationalField.DOMAIN_ID,
            first.sourceDomainId());
        assertEquals(
            BigInteger.valueOf(15),
            first.denominatorClearingFactor());
        assertEquals(
            BigInteger.valueOf(2),
            first.integerContent());
        assertEquals(q(-2, 15), first.scalar());
        assertEquals(
            integers(3, -5, 2),
            UnivariatePolynomialView.from(
                first.primitivePart()).coefficients());
        assertEquals(first, second);
        assertEquals(
            0,
            UnivariateContentResult.class
                .getConstructors().length);
    }

    @Test
    void negativeRationalConstantsUsePrimitivePartOne() {
        SparsePolynomial<ExactRational> source =
            rational(q(-6, 5));

        UnivariateContentResult result =
            UnivariateContentNormalization.normalizeRational(
                source,
                request(128, 1_000));

        assertTrue(result.completed(), result.toString());
        assertEquals(q(-6, 5), result.scalar());
        assertEquals(
            BigInteger.valueOf(5),
            result.denominatorClearingFactor());
        assertEquals(
            BigInteger.valueOf(6),
            result.integerContent());
        assertEquals(
            List.of(BigInteger.ONE),
            UnivariatePolynomialView.from(
                result.primitivePart()).coefficients());
    }

    @Test
    void structuralIntermediateAndWorkLimitsRemainInconclusive() {
        SparsePolynomial<ExactRational> quadratic =
            rational(q(1, 1), q(0, 1), q(1, 1));
        UnivariateContentResult structural =
            UnivariateContentNormalization.normalizeRational(
                quadratic,
                new UnivariateContentRequest(
                    new FactorizationRequest.StructuralLimits(
                        1,
                        1,
                        8,
                        128),
                    128,
                    1_000));
        UnivariateContentResult intermediate =
            UnivariateContentNormalization.normalizeRational(
                rational(q(1, 6), q(1, 35)),
                request(4, 1_000));
        UnivariateContentResult work =
            UnivariateContentNormalization.normalizeRational(
                quadratic,
                request(128, 1));

        assertEquals(
            UnivariateContentResult.Status.BUDGET_INCONCLUSIVE,
            structural.status());
        assertEquals(
            "MAX_TOTAL_DEGREE_EXCEEDED",
            structural.detailCode());
        assertEquals(0, structural.work().totalWorkUnits());
        assertEquals(
            UnivariateContentResult.Status.BUDGET_INCONCLUSIVE,
            intermediate.status());
        assertEquals(
            "DENOMINATOR_LCM_BIT_LENGTH_EXCEEDED",
            intermediate.detailCode());
        assertEquals(
            UnivariateContentResult.Status.BUDGET_INCONCLUSIVE,
            work.status());
        assertEquals(
            "CONTENT_NORMALIZATION_WORK_BUDGET_EXCEEDED",
            work.detailCode());
    }

    @Test
    void zeroAndMultivariateInputsFailWithoutMathematicalClaims() {
        UnivariateContentResult zero =
            UnivariateContentNormalization.normalizeInteger(
                SparsePolynomial.zero(integerRing),
                request(128, 1_000));
        PolynomialRing<BigInteger> multivariateRing =
            new PolynomialRing<>(
                BigIntegerDomain.INSTANCE,
                List.of(
                    new PolynomialVariable("x"),
                    new PolynomialVariable("y")),
                PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        SparsePolynomial<BigInteger> multivariate =
            new SparsePolynomial<>(
                multivariateRing,
                Map.of(Monomial.of(1, 0), BigInteger.ONE));
        UnivariateContentResult shape =
            UnivariateContentNormalization.normalizeInteger(
                multivariate,
                new UnivariateContentRequest(
                    new FactorizationRequest.StructuralLimits(
                        2,
                        4,
                        8,
                        128),
                    128,
                    1_000));

        assertEquals(
            UnivariateContentResult.Status.UNSUPPORTED_SHAPE,
            zero.status());
        assertEquals(
            "ZERO_POLYNOMIAL_HAS_NO_PRIMITIVE_PART",
            zero.detailCode());
        assertThrows(IllegalStateException.class, zero::scalar);
        assertEquals(
            UnivariateContentResult.Status.UNSUPPORTED_SHAPE,
            shape.status());
        assertEquals(
            "REQUIRES_ONE_POLYNOMIAL_VARIABLE",
            shape.detailCode());
    }

    private UnivariateContentRequest request(
        int maximumIntermediateBits,
        long maximumWork
    ) {
        return new UnivariateContentRequest(
            limits,
            maximumIntermediateBits,
            maximumWork);
    }

    private SparsePolynomial<BigInteger> integer(
        long... coefficients
    ) {
        return UnivariatePolynomialView.of(
            integerRing,
            integers(coefficients))
            .toSparsePolynomial();
    }

    private SparsePolynomial<ExactRational> rational(
        ExactRational... coefficients
    ) {
        return UnivariatePolynomialView.of(
            rationalRing,
            List.of(coefficients))
            .toSparsePolynomial();
    }

    private static List<BigInteger> integers(
        long... values
    ) {
        return Arrays.stream(values)
            .mapToObj(BigInteger::valueOf)
            .toList();
    }

    private static ExactRational q(
        long numerator,
        long denominator
    ) {
        return new ExactRational(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator));
    }
}
