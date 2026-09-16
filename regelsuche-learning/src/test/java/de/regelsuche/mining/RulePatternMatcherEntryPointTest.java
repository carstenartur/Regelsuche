package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RulePatternMatcherEntryPointTest {
    @Test
    void preparsedPatternKeepsTheExistingStringEntryPoint() {
        var matcher = new RulePatternMatcher();
        var pattern = new RulePatternParser().parse("(A+B)*(A-B)");
        var bindings = matcher.match(pattern, "(y+x)*(x-y)").orElseThrow();
        assertEquals("x", ExpressionFormatter.format(bindings.get("A")));
        assertEquals("y", ExpressionFormatter.format(bindings.get("B")));
        assertTrue(matcher.match(pattern, "(").isEmpty());
    }

    @Test
    void existingExtensionPointStillReceivesBothStringEntryPoints() {
        Expr sentinel = new ExpressionParser().parseTerm("sentinel");
        var matcher = new RulePatternMatcher() {
            @Override
            public Optional<Map<String, Expr>> matchExpression(RulePatternNode pattern, Expr expression) {
                return Optional.of(Map.of("custom", sentinel));
            }
        };
        var pattern = new RulePatternParser().parse("A");
        assertEquals(sentinel, matcher.match(pattern, "x").orElseThrow().get("custom"));
        assertEquals(sentinel, matcher.match("A", "x").orElseThrow().get("custom"));
    }
}
