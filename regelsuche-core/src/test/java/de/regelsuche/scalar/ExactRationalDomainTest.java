package de.regelsuche.scalar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class ExactRationalDomainTest {
    private final ExactRationalDomain domain = new ExactRationalDomain();

    @Test
    void canonicalizesSignsGreatestCommonDivisorsAndZero() {
        assertEquals(
            new ExactRational(BigInteger.valueOf(-3), BigInteger.valueOf(4)),
            new ExactRational(BigInteger.valueOf(6), BigInteger.valueOf(-8)));
        assertEquals(
            ExactRational.ZERO,
            new ExactRational(BigInteger.ZERO, BigInteger.valueOf(-19)));
        assertEquals("-3/4", new ExactRational(
            BigInteger.valueOf(6),
            BigInteger.valueOf(-8)).canonicalText());
        assertEquals("7", ExactRational.integer(7).canonicalText());
    }

    @Test
    void computesWithExactArbitraryPrecisionValues() {
        ExactRational oneThird = new ExactRational(
            BigInteger.ONE,
            BigInteger.valueOf(3));
        ExactRational twoFifths = new ExactRational(
            BigInteger.valueOf(2),
            BigInteger.valueOf(5));

        assertEquals(
            new ExactRational(BigInteger.valueOf(11), BigInteger.valueOf(15)),
            oneThird.add(twoFifths));
        assertEquals(
            new ExactRational(BigInteger.valueOf(-1), BigInteger.valueOf(15)),
            oneThird.subtract(twoFifths));
        assertEquals(
            new ExactRational(BigInteger.valueOf(2), BigInteger.valueOf(15)),
            oneThird.multiply(twoFifths));
        assertEquals(
            new ExactRational(BigInteger.valueOf(5), BigInteger.valueOf(6)),
            oneThird.divide(twoFifths));
        assertEquals(
            new ExactRational(BigInteger.ONE, BigInteger.valueOf(27)),
            oneThird.pow(3));
        assertTrue(oneThird.compareTo(twoFifths) < 0);
    }

    @Test
    void parsesIntegerFractionAndFiniteDecimalLexemesExactly() {
        ExactRationalDomain.ParseResult integer = domain.parse("-42");
        ExactRationalDomain.ParseResult fraction = domain.parse(" 6 / -8 ");
        ExactRationalDomain.ParseResult decimal = domain.parse("0.125");

        assertEquals(ExactRationalDomain.Status.EXACT, integer.status());
        assertEquals("-42", integer.canonicalValue());
        assertEquals("-3/4", fraction.canonicalValue());
        assertEquals("1/8", decimal.canonicalValue());
        assertTrue(decimal.certificateHash().matches("sha256:[0-9a-f]{64}"));
        assertTrue(decimal.valueId().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void equalLexicalFormsShareValueIdentityButRetainSourceEvidence() {
        ExactRationalDomain.ParseResult fraction = domain.parse("1/2");
        ExactRationalDomain.ParseResult decimal = domain.parse("0.50");

        assertEquals(fraction.value(), decimal.value());
        assertEquals(fraction.valueId(), decimal.valueId());
        assertNotEquals(
            fraction.certificateHash(),
            decimal.certificateHash());
        assertEquals(fraction, domain.parse("1/2"));
    }

    @Test
    void rejectsApproximateAndUndefinedFormsWithoutLeakingValues() {
        ExactRationalDomain.ParseResult exponent = domain.parse("1e-3");
        ExactRationalDomain.ParseResult repeating = domain.parse("0.(3)");
        ExactRationalDomain.ParseResult binaryMarker = domain.parse("NaN");
        ExactRationalDomain.ParseResult zeroDenominator = domain.parse("1/0");

        assertFailure(
            exponent,
            ExactRationalDomain.Status.UNSUPPORTED,
            "LITERAL_GRAMMAR_UNSUPPORTED");
        assertFailure(
            repeating,
            ExactRationalDomain.Status.UNSUPPORTED,
            "LITERAL_GRAMMAR_UNSUPPORTED");
        assertFailure(
            binaryMarker,
            ExactRationalDomain.Status.UNSUPPORTED,
            "LITERAL_GRAMMAR_UNSUPPORTED");
        assertFailure(
            zeroDenominator,
            ExactRationalDomain.Status.ZERO_DENOMINATOR,
            "RATIONAL_DENOMINATOR_ZERO");
    }

    @Test
    void finiteLimitsFailClosedBeforeLargeIntegerWork() {
        ExactRationalDomain limited = new ExactRationalDomain(
            new ExactRationalDomain.Limits(20, 5, 2));

        assertEquals(
            ExactRationalDomain.Status.LIMIT_EXCEEDED,
            limited.parse("123456").status());
        assertEquals(
            "DECIMAL_SCALE_LIMIT_EXCEEDED",
            limited.parse("1.234").detailCode());
        assertEquals(
            ExactRationalDomain.Status.LIMIT_EXCEEDED,
            limited.parse("123456789012345678901").status());
    }

    @Test
    void valueOperationsRejectUndefinedInputs() {
        assertThrows(
            ArithmeticException.class,
            () -> new ExactRational(BigInteger.ONE, BigInteger.ZERO));
        assertThrows(
            ArithmeticException.class,
            () -> ExactRational.ONE.divide(ExactRational.ZERO));
        assertThrows(
            ArithmeticException.class,
            ExactRational.ZERO::reciprocal);
        assertThrows(
            IllegalArgumentException.class,
            () -> ExactRational.ONE.pow(-1));
    }

    private void assertFailure(
        ExactRationalDomain.ParseResult result,
        ExactRationalDomain.Status status,
        String detailCode
    ) {
        assertEquals(status, result.status());
        assertEquals(detailCode, result.detailCode());
        assertFalse(result.exact());
        assertTrue(result.value().isEmpty());
        assertTrue(result.canonicalValue().isEmpty());
        assertTrue(result.valueId().isEmpty());
        assertTrue(result.certificateHash().isEmpty());
    }
}
