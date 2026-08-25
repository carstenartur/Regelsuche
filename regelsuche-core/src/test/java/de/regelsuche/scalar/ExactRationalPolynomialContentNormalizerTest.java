package de.regelsuche.scalar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void polynomialTrimsZerosAndEvaluatesExactly() {
        ExactRationalPolynomial polynomial = ExactRationalPolynomial.of(
            rational(1, 2),
            rational(-3, 4),
            rational(1, 4),
            ExactRational.ZERO);

        assertEquals(2, polynomial.degree());
        assertEquals(3, polynomial.coefficientsAscending().size());
        assertEquals(
            ExactRational.ZERO,
            polynomial.evaluate(ExactRational.ONE));
        assertEquals(
            rational(3, 2),
            polynomial.evaluate(ExactRational.integer(-1)));
        assertEquals(
            ExactRational.ZERO,
            polynomial.coefficient(9));
    }

    @Test
    void clearsDenominatorsAndRetainsPrimitivePolynomial() {
        ExactRationalPolynomialContentEvidence evidence =
            normalizer.normalize(ExactRationalPolynomial.of(
                rational(1, 2),
                rational(-3, 4),
                rational(1, 4)));
        var result = evidence.normalization().orElseThrow();

        assertTrue(evidence.normalized());
        assertEquals(
            ExactRationalPolynomialContentNormalizer.DEFAULT_BUDGET,
            evidence.budget());
        assertEquals(
            BigInteger.valueOf(4),
            result.denominatorClearingFactor());
        assertEquals(
            integers(2, -3, 1),
            result.integralCoefficientsAscending());
        assertEquals(BigInteger.ONE, result.integerContent());
        assertEquals(
            integers(2, -3, 1),
            result.primitiveCoefficientsAscending());
        assertEquals(rational(1, 4), result.scalar());
        assertTrue(evidence.work().gcdOperations() >= 6);
        assertTrue(evidence.work().reconstructionChecks() >= 6);
        assertTrue(evidence.work().totalSteps()
            <= evidence.budget().maxArithmeticSteps());
        assertEquals(
            ExactRationalPolynomialContentVerifier.Status
                .VERIFIED_NORMALIZED,
            evidence.verify().status());
    }

    @Test
    void extractsContentAndMovesLeadingSignIntoScalar() {
        var content = normalizer.normalize(ExactRationalPolynomial.of(
            rational(3, 2),
            rational(3, 1),
            rational(3, 2)))
            .normalization()
            .orElseThrow();
        var sign = normalizer.normalize(ExactRationalPolynomial.of(
            rational(1, 2),
            rational(-1, 2)))
            .normalization()
            .orElseThrow();

        assertEquals(integers(3, 6, 3),
            content.integralCoefficientsAscending());
        assertEquals(BigInteger.valueOf(3), content.integerContent());
        assertEquals(integers(1, 2, 1),
            content.primitiveCoefficientsAscending());
        assertEquals(rational(3, 2), content.scalar());
        assertEquals(integers(-1, 1),
            sign.primitiveCoefficientsAscending());
        assertEquals(rational(-1, 2), sign.scalar());
    }

    @Test
    void certificateBindsValueAndBudgetDeterministically() {
        ExactRationalPolynomial source = ExactRationalPolynomial.of(
            rational(1, 2),
            rational(-3, 4),
            rational(1, 4));
        ExactRationalPolynomial changed = ExactRationalPolynomial.of(
            rational(1, 2),
            rational(-3, 4),
            rational(1, 2));
        var changedBudget = new ExactRationalPolynomialContentNormalizer(
            new ExactRationalPolynomialContentNormalizer.Budget(
                32,
                4_096,
                131_072,
                100_001));

        var first = normalizer.normalize(source);
        assertEquals(first.certificateHash(),
            normalizer.normalize(source).certificateHash());
        assertNotEquals(first.certificateHash(),
            normalizer.normalize(changed).certificateHash());
        assertNotEquals(first.certificateHash(),
            changedBudget.normalize(source).certificateHash());
    }

    @Test
    void finiteBudgetsAndZeroPolynomialFailClosed() {
        assertFailure(
            normalizer.normalize(ExactRationalPolynomial.of(
                ExactRational.ZERO)),
            ExactRationalPolynomialContentNormalizer.Status.ZERO_POLYNOMIAL);
        assertFailure(
            normalizer(1, 128, 256, 1_000).normalize(
                ExactRationalPolynomial.of(
                    ExactRational.ONE,
                    ExactRational.ONE,
                    ExactRational.ONE)),
            ExactRationalPolynomialContentNormalizer.Status
                .DEGREE_LIMIT_EXCEEDED);
        assertFailure(
            normalizer(4, 3, 8, 1_000).normalize(
                ExactRationalPolynomial.of(
                    ExactRational.integer(16),
                    ExactRational.ONE)),
            ExactRationalPolynomialContentNormalizer.Status
                .COEFFICIENT_LIMIT_EXCEEDED);
        assertFailure(
            normalizer(4, 4, 4, 1_000).normalize(
                ExactRationalPolynomial.of(
                    rational(1, 7),
                    rational(1, 11))),
            ExactRationalPolynomialContentNormalizer.Status
                .INTERMEDIATE_LIMIT_EXCEEDED);
        assertFailure(
            normalizer(4, 128, 256, 2).normalize(
                ExactRationalPolynomial.of(
                    ExactRational.ONE,
                    ExactRational.ONE)),
            ExactRationalPolynomialContentNormalizer.Status
                .WORK_LIMIT_EXCEEDED);
    }

    @Test
    void budgetAndEvidenceConstructionFailClosed() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExactRationalPolynomialContentNormalizer.Budget(
                4,
                128,
                64,
                1_000));
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExactRationalPolynomialContentNormalizer.Budget(
                ExactRationalPolynomialContentNormalizer.MAX_DEGREE + 1,
                128,
                256,
                1_000));
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
        assertEquals(
            ExactRationalPolynomialContentVerifier.Status.VERIFIED_FAILURE,
            evidence.verify().status());
    }

    private static ExactRationalPolynomialContentNormalizer normalizer(
        int degree,
        int coefficientBits,
        int intermediateBits,
        int steps
    ) {
        return new ExactRationalPolynomialContentNormalizer(
            new ExactRationalPolynomialContentNormalizer.Budget(
                degree,
                coefficientBits,
                intermediateBits,
                steps));
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
