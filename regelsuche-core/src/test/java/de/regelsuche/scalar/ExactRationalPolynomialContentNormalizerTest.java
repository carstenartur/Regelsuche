package de.regelsuche.scalar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactRationalPolynomialContentNormalizerTest {
    private final ExactRationalPolynomialContentNormalizer normalizer =
        new ExactRationalPolynomialContentNormalizer();

    @Test
    void polynomialValueTrimsTrailingZerosAndEvaluatesExactly() {
        ExactRationalPolynomial polynomial = ExactRationalPolynomial.of(
            rational(1, 2),
            rational(-3, 4),
            rational(1, 4),
            ExactRational.ZERO,
            ExactRational.ZERO);

        assertEquals(2, polynomial.degree());
        assertEquals(3, polynomial.coefficientsAscending().size());
        assertEquals(
            ExactRational.ZERO,
            polynomial.evaluate(ExactRational.ONE));
        assertEquals(
            rational(1, 1),
            polynomial.evaluate(ExactRational.integer(-1)));
        assertEquals(
            ExactRational.ZERO,
            polynomial.coefficient(9));
    }

    @Test
    void clearsDenominatorsAndRetainsPrimitiveIntegerPolynomial() {
        ExactRationalPolynomial polynomial = ExactRationalPolynomial.of(
            rational(1, 2),
            rational(-3, 4),
            rational(1, 4));

        ExactRationalPolynomialContentEvidence evidence =
            normalizer.normalize(polynomial);
        ExactRationalPolynomialContentEvidence.Normalization result =
            evidence.normalization().orElseThrow();

        assertTrue(evidence.normalized(), evidence.toString());
        assertEquals(
            ExactRationalPolynomialContentNormalizer.Status.NORMALIZED,
            evidence.status());
        assertEquals(BigInteger.valueOf(4),
            result.denominatorClearingFactor());
        assertEquals(
            integers(2, -3, 1),
            result.integralCoefficientsAscending());
        assertEquals(BigInteger.ONE, result.integerContent());
        assertEquals(
            integers(2, -3, 1),
            result.primitiveCoefficientsAscending());
        assertEquals(rational(1, 4), result.scalar());
        assertTrue(evidence.work().totalSteps() > 0);
        assertTrue(evidence.certificateHash().matches(
            "sha256:[0-9a-f]{64}"));
    }

    @Test
    void extractsCommonIntegerContentAfterDenominatorClearing() {
        ExactRationalPolynomial polynomial = ExactRationalPolynomial.of(
            rational(3, 2),
            rational(3, 1),
            rational(3, 2));

        ExactRationalPolynomialContentEvidence.Normalization result =
            normalizer.normalize(polynomial)
                .normalization()
                .orElseThrow();

        assertEquals(BigInteger.valueOf(2),
            result.denominatorClearingFactor());
        assertEquals(integers(3, 6, 3),
            result.integralCoefficientsAscending());
        assertEquals(BigInteger.valueOf(3), result.integerContent());
        assertEquals(integers(1, 2, 1),
            result.primitiveCoefficientsAscending());
        assertEquals(rational(3, 2), result.scalar());
    }

    @Test
    void movesLeadingSignIntoTheExactScalar() {
        ExactRationalPolynomial polynomial = ExactRationalPolynomial.of(
            rational(1, 2),
            rational(-1, 2));

        ExactRationalPolynomialContentEvidence.Normalization result =
            normalizer.normalize(polynomial)
                .normalization()
                .orElseThrow();

        assertEquals(integers(1, -1),
            result.integralCoefficientsAscending());
        assertEquals(integers(-1, 1),
            result.primitiveCoefficientsAscending());
        assertEquals(rational(-1, 2), result.scalar());
    }

    @Test
    void certificateIsDeterministicAndBindsThePolynomialValue() {
        ExactRationalPolynomial first = ExactRationalPolynomial.of(
            rational(1, 2),
            rational(-3, 4),
            rational(1, 4));
        ExactRationalPolynomial second = ExactRationalPolynomial.of(
            rational(1, 2),
            rational(-3, 4),
            rational(2, 4));

        ExactRationalPolynomialContentEvidence firstRun =
            normalizer.normalize(first);
        ExactRationalPolynomialContentEvidence repeated =
            normalizer.normalize(first);
        ExactRationalPolynomialContentEvidence changed =
            normalizer.normalize(second);

        assertEquals(
            firstRun.certificateHash(),
            repeated.certificateHash());
        assertNotEquals(
            firstRun.certificateHash(),
            changed.certificateHash());
    }

    @Test
    void zeroAndFiniteBudgetsFailClosedWithoutNormalizationData() {
        ExactRationalPolynomialContentEvidence zero =
            normalizer.normalize(ExactRationalPolynomial.of(
                ExactRational.ZERO));
        ExactRationalPolynomialContentEvidence degree =
            new ExactRationalPolynomialContentNormalizer(
                new ExactRationalPolynomialContentNormalizer.Budget(
                    1,
                    128,
                    1_000))
                .normalize(ExactRationalPolynomial.of(
                    ExactRational.ONE,
                    ExactRational.ONE,
                    ExactRational.ONE));
        ExactRationalPolynomialContentEvidence coefficient =
            new ExactRationalPolynomialContentNormalizer(
                new ExactRationalPolynomialContentNormalizer.Budget(
                    4,
                    3,
                    1_000))
                .normalize(ExactRationalPolynomial.of(
                    ExactRational.integer(16),
                    ExactRational.ONE));
        ExactRationalPolynomialContentEvidence work =
            new ExactRationalPolynomialContentNormalizer(
                new ExactRationalPolynomialContentNormalizer.Budget(
                    4,
                    128,
                    2))
                .normalize(ExactRationalPolynomial.of(
                    ExactRational.ONE,
                    ExactRational.ONE));

        assertFailure(zero,
            ExactRationalPolynomialContentNormalizer.Status.ZERO_POLYNOMIAL);
        assertFailure(degree,
            ExactRationalPolynomialContentNormalizer.Status
                .DEGREE_LIMIT_EXCEEDED);
        assertFailure(coefficient,
            ExactRationalPolynomialContentNormalizer.Status
                .COEFFICIENT_LIMIT_EXCEEDED);
        assertFailure(work,
            ExactRationalPolynomialContentNormalizer.Status
                .WORK_LIMIT_EXCEEDED);
    }

    @Test
    void normalizationEvidenceIsNotConsumerConstructible() {
        Constructor<?>[] constructors =
            ExactRationalPolynomialContentEvidence.class.getConstructors();

        assertEquals(0, constructors.length);
        for (Constructor<?> constructor
                : ExactRationalPolynomialContentEvidence.class
                    .getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()));
        }
    }

    private void assertFailure(
        ExactRationalPolynomialContentEvidence evidence,
        ExactRationalPolynomialContentNormalizer.Status status
    ) {
        assertEquals(status, evidence.status());
        assertFalse(evidence.normalized());
        assertTrue(evidence.normalization().isEmpty());
        assertTrue(evidence.certificateHash().matches(
            "sha256:[0-9a-f]{64}"));
    }

    private static ExactRational rational(long numerator, long denominator) {
        return new ExactRational(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator));
    }

    private static List<BigInteger> integers(long... values) {
        return java.util.Arrays.stream(values)
            .mapToObj(BigInteger::valueOf)
            .toList();
    }
}
