package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScopedSymbolPatternMatcherTest {
    private static final String PATTERN = "(A+B)*(A-B)+B^2";
    private final RulePatternMatcher matcher = new RulePatternMatcher();

    @Test void repeatedPlaceholderChecksSymbolIdentityNotDisplayOrOccurrenceIdentity() {
        var scope = new SymbolScope(new UUID(0, 1));
        var good = SymbolicExpression.parse("(x+y)*(x-y)+y^2", scope);
        var bad = SymbolicExpression.parse("(x+y)*(x-y)+z^2", scope);
        var bindings = matcher.matchExpression(PATTERN, good.expression()).orElseThrow();
        assertEquals(scope.resolve("y"), ((VariableExpr) bindings.get("B")).symbol().orElseThrow());
        assertTrue(matcher.matchExpression(PATTERN, bad.expression()).isEmpty());
        var renamed = good.withDisplayName(scope.resolve("y"), "vertical");
        assertEquals(bindings, matcher.matchExpression(PATTERN, renamed.expression()).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> bindings.clear());
    }

    @Test void aliasesAndIndependentScopesStillMatchTheSameGeneralRule() {
        var scope = new SymbolScope(new UUID(0, 1));
        scope.alias("v", scope.resolve("y"));
        var aliased = SymbolicExpression.parse("(x+y)*(x-y)+v^2", scope);
        var other = SymbolicExpression.parse("(x+y)*(x-y)+y^2", new SymbolScope(new UUID(0, 2)));
        assertNotEquals(aliased.expression(), other.expression());
        var pattern = new RulePatternParser().parse(PATTERN);
        assertTrue(matcher.matchExpression(pattern, aliased.expression()).isPresent());
        assertTrue(matcher.matchExpression(pattern, other.expression()).isPresent());
    }

    @Test void compositePlaceholderBindingsPreserveRepeatedSubtreeRelationships() {
        var scope = new SymbolScope(new UUID(0, 1));
        var good = SymbolicExpression.parse("((s+t)+(a+b))*((s+t)-(a+b))+(a+b)^2", scope);
        var bad = SymbolicExpression.parse("((s+t)+(a+b))*((s+t)-(a+b))+(a+c)^2", scope);
        var bindings = matcher.matchExpression(PATTERN, good.expression()).orElseThrow();
        assertInstanceOf(BinaryExpr.class, bindings.get("B"));
        assertTrue(matcher.matchExpression(PATTERN, bad.expression()).isEmpty());
    }

    @Test void malformedPatternsAndLegacyStringInputsKeepTheirExistingBehavior() {
        var source = SymbolicExpression.parse("x+x", new SymbolScope(new UUID(0, 1)));
        assertTrue(matcher.matchExpression("(", source.expression()).isEmpty());
        assertTrue(matcher.match("A+A", "x+x").isPresent());
        assertTrue(matcher.match("A+A", "x+y").isEmpty());
        assertThrows(NullPointerException.class, () -> matcher.matchExpression("A", null));
    }
}
