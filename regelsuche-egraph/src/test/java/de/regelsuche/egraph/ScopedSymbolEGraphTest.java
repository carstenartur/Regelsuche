package de.regelsuche.egraph;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.VariableExpr;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import de.regelsuche.value.ExprValueFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScopedSymbolEGraphTest {
    @Test void separateScopesWithEqualLabelsDoNotCreateAnEquality() {
        var a = SymbolicExpression.parse("x+x", new SymbolScope(new UUID(0, 1)));
        var b = SymbolicExpression.parse("x+x", new SymbolScope(new UUID(0, 2)));
        var graph = new EGraph();
        var first = graph.addExpression(a.expression());
        var second = graph.addExpression(b.expression());
        assertFalse(graph.areEquivalent(first, second));
        assertEquals(first, graph.addExpression(a.withDisplayName(a.sourceBindings().get("x"), "anotherLabel").expression()));
    }

    @Test void extractionRestoresIdsWithoutClaimingOriginalOccurrenceProvenance() {
        var document = SymbolicExpression.parse("(x+y)*(x-y)+y^2", new SymbolScope(new UUID(0, 1)));
        var graph = new EGraph();
        var root = graph.addExpression(document.expression());
        var extracted = graph.extract(root, node -> 1);
        assertEquals(document.expression(), extracted);
        assertTrue(document.sourceRangeFor(extracted).isEmpty());
        assertEquals(document.displayText(), document.display(extracted));
        try (var values = new ExprValueFactory()) {
            assertEquals(values.fromExpr(document.expression()), values.fromExpr(extracted));
        }
    }

    @Test void explicitEqualityDoesNotChangeTheUnderlyingSymbolIdentity() {
        var scope = new SymbolScope(new UUID(0, 1));
        var x = VariableExpr.scoped(scope.resolve("x"));
        var y = VariableExpr.scoped(scope.resolve("y"));
        var graph = new EGraph();
        var xc = graph.addExpression(x); var yc = graph.addExpression(y);
        assertFalse(graph.areEquivalent(xc, yc));
        graph.union(xc, yc); graph.rebuild();
        assertTrue(graph.areEquivalent(xc, yc));
        assertNotEquals(x, y);
        assertNotEquals(x.symbol(), y.symbol());
    }
}
