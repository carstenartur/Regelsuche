package de.regelsuche.mining;

import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulePatternInstantiatorTest {
    private final RulePatternInstantiator instantiator = new RulePatternInstantiator();
    private final RulePatternParser parser = new RulePatternParser();
    private final ExpressionParser expressionParser = new ExpressionParser();

    @Test
    void substitutesPlaceholderInsideExpression() {
        RulePatternNode pattern = parser.parse("A^2 + 2*A + 1");

        var result = instantiator.instantiate(pattern, Map.of("A", expressionParser.parseTerm("x + 1")));

        assertEquals("(x + 1) ^ 2 + 2 * (x + 1) + 1", ExpressionFormatter.format(result));
    }

    @Test
    void instantiatesTwoPlaceholderSophieGermainTarget() {
        RulePatternNode pattern = parser.parse("(A^2 - 2*A*B + 2*B^2) * (A^2 + 2*A*B + 2*B^2)");

        var result = instantiator.instantiate(pattern, Map.of(
            "A", expressionParser.parseTerm("x + 1"),
            "B", expressionParser.parseTerm("z")
        ));

        assertTrue(new SymPyEquivalenceService().areEquivalent(
            "((x+1)^2 - 2*(x+1)*z + 2*z^2) * ((x+1)^2 + 2*(x+1)*z + 2*z^2)",
            ExpressionFormatter.format(result)
        ));
    }
}
