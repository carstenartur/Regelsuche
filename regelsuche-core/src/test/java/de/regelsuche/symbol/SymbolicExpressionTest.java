package de.regelsuche.symbol;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.canonical.PolynomialNormalizer;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.value.ExprValueFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SymbolicExpressionTest {
    private static SymbolScope scope() { return new SymbolScope(new UUID(0, 1)); }

    @Test void repeatedOccurrencesHaveOneSymbolButSeparateOriginalRanges() {
        var scope = scope();
        var document = SymbolicExpression.parse("(x+y)*(x-y)+y^2+0.25", scope);
        var ys = nodes(document.expression()).stream().filter(VariableExpr.class::isInstance)
            .map(VariableExpr.class::cast).filter(v -> v.symbol().orElseThrow().equals(scope.resolve("y"))).toList();
        assertEquals(3, ys.size());
        assertNotSame(ys.get(0), ys.get(1));
        assertSame(ys.get(0).symbol().orElseThrow(), ys.get(1).symbol().orElseThrow());
        assertEquals(3, ys.stream().map(document::sourceRangeFor).distinct().count());
        for (var y : ys) assertEquals("y", document.sourceRangeFor(y).orElseThrow().textFrom(document.original().source()));
        assertTrue(document.sourceRangeFor(VariableExpr.scoped(scope.resolve("y"))).isEmpty());
        try (var values = new ExprValueFactory()) {
            var projection = values.project(document.expression());
            assertSame(projection.valueOf(ys.get(0)).orElseThrow(), projection.valueOf(ys.get(2)).orElseThrow());
        }
    }

    @Test void numericCertificatesStayBoundToTheOriginalSourceAndActualNumberOccurrences() {
        var document = SymbolicExpression.parse("-y+0.25+9007199254740993", scope());
        var scopedNodes = nodes(document.expression());
        assertEquals(2, document.original().literals().size());
        for (var literal : document.original().literals()) {
            assertTrue(scopedNodes.stream().anyMatch(node -> node == literal.node()));
            assertSame(literal, document.original().literalFor(literal.node()).orElseThrow());
            assertEquals(literal.sourceLexeme(), document.sourceRangeFor(literal.node()).orElseThrow()
                .textFrom(document.original().source()));
        }
        var unary = (BinaryExpr) ((BinaryExpr) document.expression()).left();
        assertTrue(document.sourceRangeFor(((BinaryExpr) unary.left()).left()).isEmpty());
    }

    @Test void displayRenamingCannotChangeMathematicalIdentityOrSourceBindings() {
        var document = SymbolicExpression.parse("x+y+y", scope());
        var y = document.sourceBindings().get("y");
        var renamed = document.withDisplayName(y, "vertical");
        assertSame(document.expression(), renamed.expression());
        assertSame(document.original(), renamed.original());
        assertEquals(document.sourceBindings(), renamed.sourceBindings());
        assertEquals(document.identityText(), renamed.identityText());
        assertTrue(renamed.displayText().contains("vertical"));
        assertFalse(document.displayText().contains("vertical"));
        try (var values = new ExprValueFactory()) {
            assertSame(values.fromExpr(document.expression()), values.fromExpr(renamed.expression()));
        }
        var other = SymbolicExpression.parse("x+y+y", new SymbolScope(new UUID(0, 2)));
        assertEquals(document.displayText(), other.displayText());
        assertNotEquals(document.expression(), other.expression());
        assertThrows(UnsupportedOperationException.class, () -> document.sourceBindings().clear());
        assertThrows(UnsupportedOperationException.class, () -> document.displayNames().clear());
    }

    @Test void aliasesResolveToOneIdWithoutDiscardingTheOriginalSpelling() {
        var scope = scope();
        var y = scope.resolve("y");
        scope.alias("v", y);
        var document = SymbolicExpression.parse("y+v", scope);
        assertEquals(2, document.sourceBindings().size());
        assertEquals(1, document.displayNames().size());
        var root = (BinaryExpr) document.expression();
        assertEquals(root.left(), root.right());
        assertEquals("y+v", document.original().source());
    }

    @Test void existingNormalizerFormatterReparserAndPrimitiveEngineRetainIds() {
        var document = SymbolicExpression.parse("(x+y)*(x-y)+y^2", scope());
        var normalized = new PolynomialNormalizer().normalize(document.expression()).orElseThrow();
        var parser = new ExpressionParser();
        assertEquals(parser.parseTerm("x^2"), parser.parseTerm(document.display(normalized)));
        assertEquals(document.expression(), parser.parseTerm(document.identityText()));
        assertEquals(document.sourceBindings().get("x"), nodes(normalized).stream()
            .filter(VariableExpr.class::isInstance).map(VariableExpr.class::cast).findFirst().orElseThrow().symbol().orElseThrow());
        var simple = SymbolicExpression.parse("y+0", scope());
        var expected = VariableExpr.scoped(simple.sourceBindings().get("y"));
        assertTrue(new AstRewriteTransformationEngine().transform(simple.identityText()).stream()
            .anyMatch(step -> parser.parseTerm(step.transformedExpression()).equals(expected)));
    }

    @Test void invalidInputsAndCapacityFailuresLeaveTheScopeUnchanged() {
        var scope = new SymbolScope(new UUID(0, 1), new SymbolScope.Limits(1, 4, 2));
        var original = scope.snapshot();
        for (String invalid : List.of("x+(", "x+y", "x".repeat(129), "-".repeat(129) + "x",
                "x".repeat(SymbolicExpression.MAXIMUM_SOURCE_LENGTH + 1), "rsym_bad", "x+\ud800")) {
            assertThrows(IllegalArgumentException.class, () -> SymbolicExpression.parse(invalid, scope));
            assertEquals(original, scope.snapshot());
        }
        assertEquals(1, SymbolicExpression.parse("x", scope).sourceBindings().get("x").ordinal());
    }

    @Test void explicitBindingsRequireExactCoverageAndUnambiguousLabels() {
        var id = new SymbolId(new UUID(0, 1), 1);
        var other = new SymbolId(new UUID(0, 1), 2);
        assertThrows(IllegalArgumentException.class, () -> SymbolicExpression.fromBindings("x", Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> SymbolicExpression.fromBindings("x", Map.of("x", id, "z", other), Map.of(id, "x", other, "z")));
        assertThrows(IllegalArgumentException.class, () -> SymbolicExpression.fromBindings("x", Map.of("x", id), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> SymbolicExpression.fromBindings("x+y", Map.of("x", id, "y", other), Map.of(id, "same", other, "same")));
        var document = SymbolicExpression.fromBindings("x+y", Map.of("x", id, "y", other), Map.of(id, "first", other, "second"));
        assertThrows(IllegalArgumentException.class, () -> document.withDisplayName(id, "second"));
        assertThrows(IllegalArgumentException.class, () -> document.withDisplayName(new SymbolId(new UUID(0, 3), 1), "unknown"));
        assertThrows(IllegalArgumentException.class, () -> document.display(new VariableExpr("first")));
        assertThrows(IllegalArgumentException.class, () -> document.display(VariableExpr.scoped(new SymbolId(new UUID(0, 3), 1))));
        var swapped = document.withDisplayNames(Map.of(id, "second", other, "first"));
        assertEquals(document.expression(), swapped.expression());
    }

    @Test void constantDocumentsNeedNoSymbolsAndFunctionsKeepTheirOwnSyntax() {
        var scope = scope();
        var constant = SymbolicExpression.parse("0.25+1", scope);
        assertTrue(constant.sourceBindings().isEmpty());
        assertEquals(1, scope.snapshot().nextOrdinal());
        var function = SymbolicExpression.parse("sin(α)+α", scope);
        var changed = function.withDisplayName(function.sourceBindings().get("α"), "angle");
        assertTrue(changed.displayText().contains("sin(angle)"));
        assertEquals(function.expression(), new ExpressionParser().parseTerm(function.identityText()));
    }

    private static List<Expr> nodes(Expr root) {
        var result = new ArrayList<Expr>();
        var queue = new ArrayDeque<Expr>(); queue.add(root);
        while (!queue.isEmpty()) {
            var node = queue.remove(); result.add(node);
            if (node instanceof BinaryExpr binary) { queue.add(binary.left()); queue.add(binary.right()); }
            if (node instanceof FunctionExpr function) queue.addAll(function.arguments());
        }
        return result;
    }
}
