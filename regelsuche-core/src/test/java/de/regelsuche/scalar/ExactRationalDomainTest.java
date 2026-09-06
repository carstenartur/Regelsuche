package de.regelsuche.scalar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class ExactRationalDomainTest {
    private final ExactRationalDomain domain =
        new ExactRationalDomain();

    @Test
    void canonicalizesSignsGreatestCommonDivisorsAndZero() {
        assertEquals(
            new ExactRational(
                BigInteger.valueOf(-3),
                BigInteger.valueOf(4)),
            new ExactRational(
                BigInteger.valueOf(6),
                BigInteger.valueOf(-8)));
        assertEquals(
            ExactRational.ZERO,
            new ExactRational(
                BigInteger.ZERO,
                BigInteger.valueOf(-19)));
        assertEquals(
            "-3/4",
            new ExactRational(
                BigInteger.valueOf(6),
                BigInteger.valueOf(-8))
                .canonicalText());
        assertEquals(
            "7",
            ExactRational.integer(7).canonicalText());
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
            new ExactRational(
                BigInteger.valueOf(11),
                BigInteger.valueOf(15)),
            oneThird.add(twoFifths));
        assertEquals(
            new ExactRational(
                BigInteger.valueOf(-1),
                BigInteger.valueOf(15)),
            oneThird.subtract(twoFifths));
        assertEquals(
            new ExactRational(
                BigInteger.valueOf(2),
                BigInteger.valueOf(15)),
            oneThird.multiply(twoFifths));
        assertEquals(
            new ExactRational(
                BigInteger.valueOf(5),
                BigInteger.valueOf(6)),
            oneThird.divide(twoFifths));
        assertEquals(
            new ExactRational(
                BigInteger.ONE,
                BigInteger.valueOf(27)),
            oneThird.pow(3));
        assertTrue(oneThird.compareTo(twoFifths) < 0);
    }

    @Test
    void parsesIntegerFractionAndFiniteDecimalLexemesExactly() {
        ExactRationalParseEvidence integer =
            domain.parse("-42");
        ExactRationalParseEvidence fraction =
            domain.parse(" 6 / -8 ");
        ExactRationalParseEvidence decimal =
            domain.parse("0.50");

        assertEquals(
            ExactRationalDomain.Status.EXACT,
            integer.status());
        assertEquals("-42", integer.canonicalValue());
        assertEquals("-3/4", fraction.canonicalValue());
        assertEquals("1/2", decimal.canonicalValue());
        assertEquals(
            ExactRationalDomain.DEFAULT_LIMITS,
            decimal.limits());
        assertEquals(
            "sha256:287b26bb93278c5925a066707cdc8b3c8"
                + "cd030b306cbb28c208d90472f08890d",
            decimal.valueId());
        assertEquals(
            "sha256:e4a1084a45662f69f73aef170a08f15b"
                + "d84de7b00f16cc37d73014744c97833c",
            decimal.certificateHash());
    }

    @Test
    void legacyDecimalBridgeRequiresExactShortestDecimalRoundTrip() {
        assertEquals(
            new ExactRational(BigInteger.ONE, BigInteger.TEN),
            ExactRationalDomain.legacyDecimalValue(0.1)
                .orElseThrow());
        assertEquals(
            ExactRational.integer(BigInteger.TEN.pow(20)),
            ExactRationalDomain.legacyDecimalValue(1.0e20)
                .orElseThrow());
        assertTrue(ExactRationalDomain.legacyDecimalValue(Double.NaN).isEmpty());
        assertTrue(ExactRationalDomain.legacyDecimalValue(Double.POSITIVE_INFINITY).isEmpty());

        ExactRational threeHalves = new ExactRational(
            BigInteger.valueOf(3),
            BigInteger.valueOf(2));
        assertEquals(
            1.5,
            ExactRationalDomain.exactLegacyDecimalDouble(threeHalves)
                .orElseThrow());
        assertTrue(ExactRationalDomain.exactLegacyDecimalDouble(
            new ExactRational(BigInteger.valueOf(2), BigInteger.valueOf(3)))
            .isEmpty());
        assertTrue(ExactRationalDomain.exactLegacyDecimalDouble(
            ExactRational.integer(new BigInteger("9007199254740993")))
            .isEmpty());
    }

    @Test
    void equalLexicalFormsShareValueIdentityButRetainSourceEvidence() {
        ExactRationalParseEvidence fraction =
            domain.parse("1/2");
        ExactRationalParseEvidence decimal =
            domain.parse("0.50");

        assertEquals(fraction.value(), decimal.value());
        assertEquals(fraction.valueId(), decimal.valueId());
        assertNotEquals(
            fraction.certificateHash(),
            decimal.certificateHash());
        assertEquals(fraction, domain.parse("1/2"));
    }

    @Test
    void semanticVerifierRejectsNoncanonicalOrReboundEvidence() {
        ExactRationalParseEvidence accepted =
            domain.parse("2/4");
        ExactRationalEvidenceVerifier verifier =
            new ExactRationalEvidenceVerifier();

        assertEquals(
            ExactRationalEvidenceVerifier.Status.VERIFIED_EXACT,
            accepted.verify().status());

        ExactRationalEvidenceVerifier.SerializedEvidence serialized =
            accepted.serialized();
        ExactRationalEvidenceVerifier.SerializedEvidence noncanonical =
            copyWith(
                serialized,
                serialized.sourceLiteral(),
                "2/4",
                serialized.valueId(),
                serialized.certificateHash());
        ExactRationalEvidenceVerifier.SerializedEvidence rebound =
            copyWith(
                serialized,
                "3/6",
                serialized.canonicalValue(),
                serialized.valueId(),
                serialized.certificateHash());

        assertEquals(
            ExactRationalEvidenceVerifier.Status.REJECTED,
            verifier.verify(noncanonical).status());
        assertEquals(
            ExactRationalEvidenceVerifier.Status.REJECTED,
            verifier.verify(rebound).status());
    }

    @Test
    void evidenceCannotBeConstructedAsAConsumerApi() {
        Constructor<?>[] constructors =
            ExactRationalParseEvidence.class.getConstructors();

        assertEquals(0, constructors.length);
        for (Constructor<?> constructor
                : ExactRationalParseEvidence.class
                    .getDeclaredConstructors()) {
            assertFalse(
                Modifier.isPublic(
                    constructor.getModifiers()));
        }
    }

    @Test
    void rejectsApproximateControlAndUndefinedForms() {
        assertFailure(
            domain.parse("1e-3"),
            ExactRationalDomain.Status.UNSUPPORTED,
            "LITERAL_GRAMMAR_UNSUPPORTED");
        assertFailure(
            domain.parse("0.(3)"),
            ExactRationalDomain.Status.UNSUPPORTED,
            "LITERAL_GRAMMAR_UNSUPPORTED");
        assertFailure(
            domain.parse("NaN"),
            ExactRationalDomain.Status.UNSUPPORTED,
            "LITERAL_GRAMMAR_UNSUPPORTED");
        assertFailure(
            domain.parse("\u00001\u0000"),
            ExactRationalDomain.Status.UNSUPPORTED,
            "LITERAL_GRAMMAR_UNSUPPORTED");
        assertFailure(
            domain.parse("1/0"),
            ExactRationalDomain.Status.ZERO_DENOMINATOR,
            "RATIONAL_DENOMINATOR_ZERO");
    }

    @Test
    void zeroDenominatorIsClassifiedBeforeDigitBudgetWork() {
        ExactRationalDomain limited =
            new ExactRationalDomain(
                new ExactRationalDomain.Limits(
                    20,
                    2,
                    1));

        ExactRationalParseEvidence result =
            limited.parse("1/000000");

        assertEquals(
            ExactRationalDomain.Status.ZERO_DENOMINATOR,
            result.status());
        assertEquals(
            "RATIONAL_DENOMINATOR_ZERO",
            result.detailCode());
    }

    @Test
    void rawCharacterLimitIsCheckedBeforeWhitespaceStripping() {
        ExactRationalDomain limited =
            new ExactRationalDomain(
                new ExactRationalDomain.Limits(
                    20,
                    5,
                    2));
        String padded = " ".repeat(20) + "1";

        ExactRationalParseEvidence result =
            limited.parse(padded);

        assertEquals(
            ExactRationalDomain.Status.LIMIT_EXCEEDED,
            result.status());
        assertEquals(
            "LITERAL_CHARACTER_LIMIT_EXCEEDED",
            result.detailCode());
        assertEquals(20, result.sourceLiteral().length());
        assertTrue(result.verify().verified());
    }

    @Test
    void finiteLimitsFailClosedBeforeLargeIntegerWork() {
        ExactRationalDomain limited =
            new ExactRationalDomain(
                new ExactRationalDomain.Limits(
                    20,
                    5,
                    2));

        assertEquals(
            ExactRationalDomain.Status.LIMIT_EXCEEDED,
            limited.parse("123456").status());
        assertEquals(
            "DECIMAL_SCALE_LIMIT_EXCEEDED",
            limited.parse("1.234").detailCode());
        assertEquals(
            ExactRationalDomain.Status.LIMIT_EXCEEDED,
            limited.parse(
                "123456789012345678901").status());
    }

    @Test
    void versionedLimitsRejectUnserializableRanges() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExactRationalDomain.Limits(
                4_097,
                1_024,
                256));
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExactRationalDomain.Limits(
                100,
                5,
                6));
    }

    @Test
    void valueOperationsRejectUndefinedInputs() {
        assertThrows(
            ArithmeticException.class,
            () -> new ExactRational(
                BigInteger.ONE,
                BigInteger.ZERO));
        assertThrows(
            ArithmeticException.class,
            () -> ExactRational.ONE.divide(
                ExactRational.ZERO));
        assertThrows(
            ArithmeticException.class,
            ExactRational.ZERO::reciprocal);
        assertThrows(
            IllegalArgumentException.class,
            () -> ExactRational.ONE.pow(-1));
    }

    private ExactRationalEvidenceVerifier.SerializedEvidence copyWith(
        ExactRationalEvidenceVerifier.SerializedEvidence source,
        String sourceLiteral,
        String canonical,
        String valueId,
        String certificate
    ) {
        return new ExactRationalEvidenceVerifier.SerializedEvidence(
            source.domainId(),
            source.status(),
            source.detailCode(),
            sourceLiteral,
            source.limits(),
            canonical,
            valueId,
            certificate);
    }

    private void assertFailure(
        ExactRationalParseEvidence result,
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
        assertTrue(result.verify().verified());
    }
}
