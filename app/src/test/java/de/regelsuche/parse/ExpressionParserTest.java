package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import org.junit.jupiter.api.Test;

class ExpressionParserTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void parsesTermToAst() {
        String formatted = ExpressionFormatter.format(parser.parseTerm("2*x + 3"));
        assertEquals("2 * x + 3", formatted);
    }

    @Test
    void parsesEquationToAst() {
        String formatted = ExpressionFormatter.format(parser.parseEquation("x + 1 = 3"));
        assertEquals("x + 1 = 3", formatted);
    }

    @Test
    void parsesEquationSystemInput() {
        ParsedInput parsed = parser.parse(new InputRequest(InputType.SYSTEM, "x+1=2; y-1=0"));
        assertEquals(2, parsed.equations().size());
        assertEquals("x + 1 = 2", ExpressionFormatter.format(parsed.equations().get(0)));
        assertEquals("y - 1 = 0", ExpressionFormatter.format(parsed.equations().get(1)));
    }
}
