package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionFormatter;
import org.junit.jupiter.api.Test;

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

    @Test
    void matchesRepeatedPlaceholderAcrossAssociativeGrouping() {
        var bindings = matcher.match(
            "A * A * A * A",
            "((x + 1) * (x + 1)) * ((x + 1) * (x + 1))");

        assertTrue(bindings.isPresent());
        assertEquals("x + 1", ExpressionFormatter.format(bindings.get().get("A")));
        assertTrue(matcher.match(
            "A * A * A * A",
            "((x + 1) * (x + 1)) * ((x + 1) * (x + 2))").isEmpty());
    }
}
