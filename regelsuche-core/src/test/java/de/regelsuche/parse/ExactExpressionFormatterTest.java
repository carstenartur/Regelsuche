package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.ast.NumberExpr;
import org.junit.jupiter.api.Test;

class ExactExpressionFormatterTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void preservesLargeExactValuesAndCanonicalizesFiniteDecimals() {
        ExactParsedTerm parsed = parser.parseExactTerm(
            "9007199254740993 + 0002.0 + 0.10");

        assertEquals(
            "9007199254740993 + 2 + 0.1",
            ExactExpressionFormatter.format(
                parsed.expression(),
                parsed));
    }

    @Test
    void explicitFractionsAndSyntheticUnaryZeroRoundTripStructurally() {
        ExactParsedTerm parsed = parser.parseExactTerm("-x + 1 / 4");

        assertEquals(
            "0 - x + 1 / 4",
            ExactExpressionFormatter.format(
                parsed.expression(),
                parsed));
    }

    @Test
    void foreignNumericNodesWithoutProvenanceFailClosed() {
        ExactParsedTerm parsed = parser.parseExactTerm("x + 1");

        assertThrows(IllegalArgumentException.class, () ->
            ExactExpressionFormatter.format(new NumberExpr(2), parsed));
    }
}
