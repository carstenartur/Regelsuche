package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.NumberExpr;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.scalar.ExactRationalEvidenceVerifier;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class ExpressionParserExactLiteralTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void retainsSourceRangesAndVerifiedExactValuesWithoutChangingTheAst() {
        String source = " 0.10*x + 0002 ";
        ExactParsedTerm parsed = parser.parseExactTerm(source);

        assertEquals("0.1 * x + 2", ExpressionFormatter.format(
            parsed.expression()));
        assertEquals(parsed.expression(), parser.parseTerm(source));
        assertEquals(2, parsed.literals().size());

        ExactParsedTerm.LiteralOccurrence decimal =
            parsed.literals().get(0);
        assertEquals(1, decimal.startInclusive());
        assertEquals(5, decimal.endExclusive());
        assertEquals("0.10", decimal.sourceLexeme());
        assertEquals(
            new ExactRational(BigInteger.ONE, BigInteger.TEN),
            decimal.exactValue());
        assertEquals(
            ExactRationalEvidenceVerifier.Status.VERIFIED_EXACT,
            decimal.evidence().verify().status());
        assertSame(
            decimal,
            parsed.literalFor(decimal.node()).orElseThrow());

        ExactParsedTerm.LiteralOccurrence integer =
            parsed.literals().get(1);
        assertEquals(10, integer.startInclusive());
        assertEquals(14, integer.endExclusive());
        assertEquals("0002", integer.sourceLexeme());
        assertEquals(ExactRational.integer(2), integer.exactValue());

        assertTrue(parsed.literalFor(new NumberExpr(0.1)).isEmpty());
    }

    @Test
    void unaryMinusRetainsOnlyTheSourceTokenAndNotItsSyntheticZero() {
        ExactParsedTerm parsed = parser.parseExactTerm("-0.25");

        assertEquals("0 - 0.25", ExpressionFormatter.format(
            parsed.expression()));
        assertEquals(1, parsed.literals().size());
        assertEquals("0.25", parsed.literals().getFirst().sourceLexeme());
        assertEquals(
            new ExactRational(
                BigInteger.ONE,
                BigInteger.valueOf(4)),
            parsed.literals().getFirst().exactValue());
    }

    @Test
    void explicitFractionsRetainBothExactLeafLexemes() {
        ExactParsedTerm parsed = parser.parseExactTerm("01 / 004");

        assertEquals("1 / 4", ExpressionFormatter.format(
            parsed.expression()));
        assertEquals(2, parsed.literals().size());
        assertEquals("01", parsed.literals().get(0).sourceLexeme());
        assertEquals("004", parsed.literals().get(1).sourceLexeme());
        assertEquals(
            ExactRational.ONE,
            parsed.literals().get(0).exactValue());
        assertEquals(
            ExactRational.integer(4),
            parsed.literals().get(1).exactValue());
    }

    @Test
    void rejectsUnsupportedOrUnsafeLegacyAstRepresentations() {
        String overflow = "9".repeat(309);
        String underflow = "0." + "0".repeat(255) + "1";
        String digitLimit = "9".repeat(1_025);

        assertThrows(
            IllegalArgumentException.class,
            () -> parser.parseExactTerm("1."));
        assertThrows(
            IllegalArgumentException.class,
            () -> parser.parseExactTerm(overflow));
        assertThrows(
            IllegalArgumentException.class,
            () -> parser.parseExactTerm(underflow));
        assertThrows(
            IllegalArgumentException.class,
            () -> parser.parseExactTerm(digitLimit));
    }
}
