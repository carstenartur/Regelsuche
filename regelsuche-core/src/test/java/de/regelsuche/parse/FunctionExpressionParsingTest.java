package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import org.junit.jupiter.api.Test;

class FunctionExpressionParsingTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void parsesFunctionExpressions() {
        ParsedInput parsed = parser.parse(new InputRequest(InputType.TERM, "sin(x) + cos(2*y)"));
        assertEquals(1, parsed.terms().size());
        Expr expr = parsed.terms().get(0);
        BinaryExpr sum = assertInstanceOf(BinaryExpr.class, expr);
        FunctionExpr sin = assertInstanceOf(FunctionExpr.class, sum.left());
        FunctionExpr cos = assertInstanceOf(FunctionExpr.class, sum.right());
        assertEquals("sin", sin.name());
        assertEquals(1, sin.arguments().size());
        assertEquals("cos", cos.name());
        BinaryExpr cosArg = assertInstanceOf(BinaryExpr.class, cos.arguments().get(0));
        assertEquals("2 * y", ExpressionFormatter.format(cosArg));
    }

    @Test
    void formatterRoundTripsAllSupportedFunctions() {
        for (String name : new String[] {"sin", "cos", "tan", "log", "ln", "sqrt", "exp", "abs"}) {
            String input = name + "(x + 1)";
            ParsedInput parsed = parser.parse(new InputRequest(InputType.TERM, input));
            FunctionExpr fn = assertInstanceOf(FunctionExpr.class, parsed.terms().get(0));
            assertEquals(name, fn.name());
            assertEquals(input, ExpressionFormatter.format(fn));
        }
    }

    @Test
    void differentFunctionsAreNotEqual() {
        Expr a = parser.parse(new InputRequest(InputType.TERM, "sin(x)")).terms().get(0);
        Expr b = parser.parse(new InputRequest(InputType.TERM, "cos(x)")).terms().get(0);
        assertNotEquals(a, b);
    }
}
