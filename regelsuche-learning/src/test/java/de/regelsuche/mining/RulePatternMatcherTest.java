package de.regelsuche.mining;

import de.regelsuche.parse.ExpressionFormatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulePatternMatcherTest {
    private final RulePatternMatcher matcher = new RulePatternMatcher();

    @Test
    void matchesTwoPlaceholderSophieGermainPattern() {
        var bindings = matcher.match("A^4 + 4*B^4", "(x+1)^4 + 4*z^4");

        assertTrue(bindings.isPresent());
        assertEquals("x + 1", ExpressionFormatter.format(bindings.get().get("A")));
        assertEquals("z", ExpressionFormatter.format(bindings.get().get("B")));

        assertTrue(matcher.match("A^4 + 4*B^4", "(x+1)^4 + 5*z^4").isEmpty());
    }
}
