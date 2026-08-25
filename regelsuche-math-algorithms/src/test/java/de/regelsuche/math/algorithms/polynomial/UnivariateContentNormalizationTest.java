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
                integerRequest(source, limits, 10_000),
                policy(4_096));

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
        FactorizationRequest<ExactRational> request =
            rationalRequest(source, limits, 10_000);
        UnivariateContentPolicy policy = policy(4_096);

        UnivariateContentResult first =
            UnivariateContentNormalization.normalizeRational(
                request,
                policy);
        UnivariateContentResult second =
            UnivariateContentNormalization.normalizeRational(
                request,
                policy);

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
                rationalRequest(source, limits, 1_000),
                policy(128));

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
                rationalRequest(
                    quadratic,
                    new FactorizationRequest.StructuralLimits(
                        1,
                        1,
                        8,
                        128),
                    1_000),
                policy(128));
        SparsePolynomial<ExactRational> growing =
            rational(q(1, 6), q(1, 35));
        UnivariateContentResult intermediate =
            UnivariateContentNormalization.normalizeRational(
                rationalRequest(growing, limits, 1_000),
                policy(4));
        UnivariateContentResult work =
            UnivariateContentNormalization.normalizeRational(
                rationalRequest(quadratic, limits, 1),
                policy(128));

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
    void aSharedFactorizationBudgetCannotBeResetOrReauthorized() {
        SparsePolynomial<ExactRational> source =
            rational(q(-2, 5), q(2, 3), q(-4, 15));
        UnivariateContentPolicy policy = policy(4_096);
        UnivariateContentResult calibration =
            UnivariateContentNormalization.normalizeRational(
                rationalRequest(source, limits, 10_000),
                policy);
        long oneRun = calibration.work().totalWorkUnits();
        FactorizationRequest<ExactRational> request =
            rationalRequest(source, limits, oneRun + 1);
        UnivariateContentResult mismatched =
            UnivariateContentNormalization.normalizeRational(
                request,
                policy,
                new PolynomialWorkBudget(oneRun + 2));
        PolynomialWorkBudget shared =
            new PolynomialWorkBudget(oneRun + 1);

        UnivariateContentResult first =
            UnivariateContentNormalization.normalizeRational(
                request,
                policy,
                shared);
        UnivariateContentResult second =
            UnivariateContentNormalization.normalizeRational(
                request,
                policy,
                shared);

        assertEquals(
            UnivariateContentResult.Status.TECHNICAL_FAILURE,
            mismatched.status());
        assertEquals(
            "CONTENT_NORMALIZATION_WORK_BUDGET_AUTHORITY_MISMATCH",
            mismatched.detailCode());
        assertEquals(0, mismatched.work().totalWorkUnits());
        assertTrue(first.completed(), first.toString());
        assertEquals(
            UnivariateContentResult.Status.BUDGET_INCONCLUSIVE,
            second.status());
        assertEquals(
            "CONTENT_NORMALIZATION_WORK_BUDGET_EXCEEDED",
            second.detailCode());
        assertTrue(
            second.work().totalWorkUnits() > oneRun);
        assertTrue(
            second.work().totalWorkUnits() <= oneRun + 1);
    }

    @Test
    void invalidZeroAndMultivariateRequestsFailBeforeClaims() {
        assertThrows(IllegalArgumentException.class, () ->
            integerRequest(
                SparsePolynomial.zero(integerRing),
                limits,
                1_000));
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
                integerRequest(
                    multivariate,
                    new FactorizationRequest.StructuralLimits(
                        2,
                        4,
                        8,
                        128),
                    1_000),
                policy(128));

        assertEquals(
            UnivariateContentResult.Status.UNSUPPORTED_SHAPE,
            shape.status());
        assertEquals(
            "REQUIRES_ONE_POLYNOMIAL_VARIABLE",
            shape.detailCode());
        assertThrows(
            IllegalStateException.class,
            shape::scalar);
    }

    private static UnivariateContentPolicy policy(
        int maximumIntermediateBits
    ) {
        return new UnivariateContentPolicy(
            maximumIntermediateBits);
    }

    private static FactorizationRequest<BigInteger> integerRequest(
        SparsePolynomial<BigInteger> source,
        FactorizationRequest.StructuralLimits structuralLimits,
        long maximumWork
    ) {
        return FactorizationRequest.verifiedDecomposition(
            source,
            structuralLimits,
            1,
            maximumWork);
    }

    private static FactorizationRequest<ExactRational> rationalRequest(
        SparsePolynomial<ExactRational> source,
        FactorizationRequest.StructuralLimits structuralLimits,
        long maximumWork
    ) {
        return FactorizationRequest.verifiedDecomposition(
            source,
            structuralLimits,
            1,
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
