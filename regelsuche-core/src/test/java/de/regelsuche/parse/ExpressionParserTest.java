package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.ast.Expr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import org.junit.jupiter.api.Test;

class ExpressionParserTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void parsesTermToAst() {
        String formatted = ExpressionFormatter.format(
            parser.parseTerm("2*x + 3"));
        assertEquals("2 * x + 3", formatted);
    }

    @Test
    void parsesEquationToAst() {
        String formatted = ExpressionFormatter.format(
            parser.parseEquation("x + 1 = 3"));
        assertEquals("x + 1 = 3", formatted);
    }

    @Test
    void parsesEquationSystemInput() {
        ParsedInput parsed = parser.parse(new InputRequest(
            InputType.SYSTEM,
            "x+1=2; y-1=0"));
        assertEquals(2, parsed.equations().size());
        assertEquals(
            "x + 1 = 2",
            ExpressionFormatter.format(parsed.equations().get(0)));
        assertEquals(
            "y - 1 = 0",
            ExpressionFormatter.format(parsed.equations().get(1)));
    }

    @Test
    void exponentiationBindsTighterThanUnaryMinus() {
        assertEquals(
            "0 - x ^ 2",
            ExpressionFormatter.format(parser.parseTerm("-x^2")));
        assertEquals(
            "(0 - x) ^ 2",
            ExpressionFormatter.format(parser.parseTerm("(-x)^2")));
    }

    @Test
    void formatsNestedPowersWithoutChangingAssociativity() {
        assertEquals(
            "(x ^ 2) ^ 3",
            ExpressionFormatter.format(parser.parseTerm("(x^2)^3")));
        assertEquals(
            "x ^ 2 ^ 3",
            ExpressionFormatter.format(parser.parseTerm("x^2^3")));
    }

    @Test
    void formatsNoncommutativeRightChildrenWithRequiredParentheses() {
        assertEquals(
            "a / (b / c)",
            ExpressionFormatter.format(parser.parseTerm("a/(b/c)")));
        assertEquals(
            "a - (b - c)",
            ExpressionFormatter.format(parser.parseTerm("a-(b-c)")));
        assertEquals(
            "a / b / c",
            ExpressionFormatter.format(parser.parseTerm("(a/b)/c")));
        assertEquals(
            "a - b - c",
            ExpressionFormatter.format(parser.parseTerm("(a-b)-c")));
    }

    @Test
    void formatsQuotientFactorsWithoutChangingProductGrouping() {
        Expr original = parser.parseTerm("0*(1/0)");
        String formatted = ExpressionFormatter.format(original);

        assertEquals("0 * (1 / 0)", formatted);
        assertEquals(original, parser.parseTerm(formatted));
    }

    @Test
    void formatsFunctionsAndNestedArgumentsInSourceOrder() {
        assertEquals(
            "f(x + 1, g(y, z ^ 2), 3)",
            ExpressionFormatter.format(
                parser.parseTerm("f(x+1,g(y,z^2),3)")));
    }

    @Test
    void formatsLongLeftAssociativeTreesWithoutStackRecursion() {
        int termCount = 12_000;
        StringBuilder source = new StringBuilder(termCount * 4);
        for (int index = 0; index < termCount; index++) {
            if (index > 0) {
                source.append(" + ");
            }
            source.append('x');
        }

        String formatted = ExpressionFormatter.format(
            parser.parseTerm(source.toString()));

        assertEquals(source.toString(), formatted);
    }
}
