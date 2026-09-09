package example;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.parse.ExpressionParser;
import org.junit.jupiter.api.Test;

class HelloRuleTest {
    @Test void loadsAndReplaysExactIdentity() {
        var rule = HelloRule.registry().enabledRules().getFirst();
        var parser = new ExpressionParser();
        var before = parser.parseTerm("x + 0");
        assertTrue(rule.matches(before));
        assertEquals(parser.parseTerm("x"), rule.apply(before));
        assertEquals(rule.apply(before), rule.apply(before));
    }
    @Test void refusesDifferentRightOperand() {
        var rule = HelloRule.registry().enabledRules().getFirst();
        assertFalse(rule.matches(new ExpressionParser().parseTerm("x + 1")));
    }
}
