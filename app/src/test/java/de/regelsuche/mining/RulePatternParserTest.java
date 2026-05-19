package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RulePatternParserTest {
    private final RulePatternParser parser = new RulePatternParser();
    private final RulePatternInstantiator instantiator = new RulePatternInstantiator();

    @Test
    void parsesPatternPlaceholdersAsPatternVariables() {
        RulePatternNode parsed = parser.parse("A*B + N1^2");

        PatternBinary addition = assertInstanceOf(PatternBinary.class, parsed);
        assertEquals(BinaryOperator.ADD, addition.op());
        PatternBinary multiplication = assertInstanceOf(PatternBinary.class, addition.left());
        assertEquals(new PatternVariable("A"), multiplication.left());
        assertEquals(new PatternVariable("B"), multiplication.right());
    }

    @Test
    void instantiatesPatternAstInsteadOfReplacingStrings() {
        Expr instantiated = instantiator.instantiate(
            parser.parse("A*A + (A)^2 + 2 * A + A^3 + A*B - A"),
            Map.of("A", new NumberExpr(3), "B", new NumberExpr(5))
        );

        assertEquals("3 * 3 + 3 ^ 2 + 2 * 3 + 3 ^ 3 + 3 * 5 - 3", ExpressionFormatter.format(instantiated));
    }

    @Test
    void keepsUnboundSymbolicVariablesAsExpressionVariables() {
        Expr instantiated = instantiator.instantiate(parser.parse("x + A"), Map.of("A", new NumberExpr(4)));

        BinaryExpr binary = assertInstanceOf(BinaryExpr.class, instantiated);
        assertEquals("x + 4", ExpressionFormatter.format(binary));
    }
}
