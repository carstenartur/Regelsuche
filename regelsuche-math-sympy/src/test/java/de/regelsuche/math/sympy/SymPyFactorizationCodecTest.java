package de.regelsuche.math.sympy;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SymPyFactorizationCodecTest {
    private static final SymPyFactorizationPolicy SMALL_COEFFICIENT_POLICY =
        new SymPyFactorizationPolicy(
            SymPyFactorizationPolicy.PINNED_SYMPY_VERSION,
            Duration.ofSeconds(1),
            256,
            4_096,
            8,
            8,
            16,
            8);

    @Test
    void rejectsOversizedIntegerTextBeforeBigIntegerConstruction() {
        String output = output(
            "1000",
            "1",
            factor("1", 1, 1));

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> SymPyFactorizationCodec.integers().decode(
                output,
                source(),
                SMALL_COEFFICIENT_POLICY));

        assertTrue(failure.getMessage().contains(
            "exceeds coefficient policy"));
    }

    @Test
    void rejectsNoncanonicalNegativeZeroCoefficientText() {
        String output = output(
            "-0",
            "1",
            factor("1", 1, 1));

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> SymPyFactorizationCodec.integers().decode(
                output,
                source(),
                SMALL_COEFFICIENT_POLICY));

        assertTrue(failure.getMessage().contains(
            "is not canonical integer text"));
    }

    @Test
    void rejectsFactorExponentBeyondTheSourceDegree() {
        String output = output(
            "1",
            "1",
            factor("1", 3, 1));

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> SymPyFactorizationCodec.integers().decode(
                output,
                source(),
                SMALL_COEFFICIENT_POLICY));

        assertTrue(failure.getMessage().contains(
            "factor exponent exceeds source degree"));
    }

    @Test
    void rejectsFactorMultiplicityBeyondTheSourceDegree() {
        String output = output(
            "1",
            "1",
            factor("1", 1, 3));

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> SymPyFactorizationCodec.integers().decode(
                output,
                source(),
                SMALL_COEFFICIENT_POLICY));

        assertTrue(failure.getMessage().contains(
            "represented factor degree exceeds source degree"));
    }

    @Test
    void rejectsZeroUnitBeforeIssuingAProposal() {
        String output = output(
            "0",
            "1",
            factor("1", 2, 1));

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> SymPyFactorizationCodec.integers().decode(
                output,
                source(),
                SMALL_COEFFICIENT_POLICY));

        assertTrue(failure.getMessage().contains(
            "unit must be nonzero"));
    }

    @Test
    void rejectsAnIncompleteRepresentedFactorDegree() {
        String output = output(
            "1",
            "1",
            factor("1", 1, 1));

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> SymPyFactorizationCodec.integers().decode(
                output,
                source(),
                SMALL_COEFFICIENT_POLICY));

        assertTrue(failure.getMessage().contains(
            "represented factor degree does not match source degree"));
    }

    private static SparsePolynomial<BigInteger> source() {
        PolynomialRing<BigInteger> ring = new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        return UnivariatePolynomialView.of(
            ring,
            List.of(
                BigInteger.ONE.negate(),
                BigInteger.ZERO,
                BigInteger.ONE))
            .toSparsePolynomial();
    }

    private static String factor(
        String coefficient,
        int degree,
        int multiplicity
    ) {
        return """
            {
              "multiplicity": %d,
              "terms": [
                {
                  "denominator": "1",
                  "exponents": [%d],
                  "numerator": "%s"
                },
                {
                  "denominator": "1",
                  "exponents": [0],
                  "numerator": "1"
                }
              ]
            }
            """.formatted(multiplicity, degree, coefficient);
    }

    private static String output(
        String unitNumerator,
        String unitDenominator,
        String factors
    ) {
        return """
            {
              "domain": "ZZ",
              "factorNanos": 1,
              "factors": [%s],
              "protocol": "regelsuche.sympy-factorization/v1",
              "pythonImplementation": "graalpy",
              "pythonVersion": "3.12.8",
              "sympyVersion": "1.14.0",
              "totalNanos": 2,
              "unit": {
                "denominator": "%s",
                "numerator": "%s"
              }
            }
            """.formatted(factors, unitDenominator, unitNumerator);
    }
}
