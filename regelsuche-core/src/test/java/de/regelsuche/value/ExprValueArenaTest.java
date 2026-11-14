package de.regelsuche.value;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.symbol.SymbolId;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExprValueArenaTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test void agreesWithExistingSemanticEqualityAcrossFormsAndOwners() {
        List<String> sources = List.of("a+(b+a)", "(a+a)+b", "a+b", "a*b", "b*a",
            "a-b", "b-a", "a/b", "b/a", "a^b", "b^a", "f(a,b)", "f(b,a)",
            "2/6", "1/3", "4-3", "1", "1/0", "0/1", "0", "9007199254740993", "9007199254740992");
        var expressions = sources.stream().map(parser::parseTerm).toList();
        try (var reference = new ExprValueFactory(); var arena = new ExprValueArena(100);
             var other = new ExprValueArena(100)) {
            var values = expressions.stream().map(reference::fromExpr).toList();
            var ids = expressions.stream().map(arena::intern).toList();
            other.intern(new VariableExpr("allocatedFirst"));
            expressions.reversed().forEach(other::intern);
            for (int i = 0; i < values.size(); i++) {
                assertEquals(arena.digest(ids.get(i)), other.digest(other.intern(expressions.get(i))));
                for (int j = 0; j < i; j++) {
                    assertEquals(values.get(i).equals(values.get(j)), ids.get(i).equals(ids.get(j)),
                        sources.get(i) + " vs " + sources.get(j));
                    assertEquals(ids.get(i).equals(ids.get(j)), arena.digest(ids.get(i)).equals(arena.digest(ids.get(j))));
                }
            }
            assertThrows(IllegalArgumentException.class, () -> other.digest(ids.getFirst()));
        }
    }

    @Test void indexHashCollisionsKeepDistinctChildrenAndExactSymbolScopes() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        try (var arena = new ExprValueArena(20)) {
            var left = new VariableExpr("Aa");
            var right = new VariableExpr("BB");
            var a = arena.intern(left);
            var b = arena.intern(right);
            assertNotEquals(a, b);
            assertNotEquals(arena.intern(new FunctionExpr("f", left)), arena.intern(new FunctionExpr("f", right)));
            var first = VariableExpr.scoped(new SymbolId(new UUID(0, 1), 3));
            var next = VariableExpr.scoped(new SymbolId(new UUID(0, 2), 3));
            assertNotEquals(arena.digest(arena.intern(first)), arena.digest(arena.intern(next)));
            assertEquals(arena.intern(first), arena.intern(new VariableExpr(first.name())));
            assertNotEquals(arena.digest(arena.intern(new VariableExpr("\uD800"))),
                arena.digest(arena.intern(new VariableExpr("\uD801"))));
        }
    }

    @Test void stableIdentityHasVersionedGoldenVectors() {
        try (var arena = new ExprValueArena(12)) {
            String prefix = ExprValueArena.DIGEST_VERSION + ":";
            assertEquals(prefix + "b37cce5243904b65387189b289c3c3cf8b14101a20643236343e400502729a19",
                arena.digest(arena.intern(parser.parseTerm("a"))));
            assertEquals(prefix + "f3c663b67359e3cf385f7e7550c1c9bbd90dd2ed657c50e046c7106710449e21",
                arena.digest(arena.intern(parser.parseTerm("pair(a,b)"))));
            assertEquals(prefix + "e66f983258268706dc002e146d9b76de611b092a1212de72587ddac894f69cae",
                arena.digest(arena.intern(parser.parseTerm("a+(b+a)"))));
        }
    }

    @Test void deepSharedDagRequiresOnlyOneEntryPerLevel() {
        Expr expression = new VariableExpr("x");
        for (int level = 0; level < 4000; level++)
            expression = new BinaryExpr(expression, BinaryOperator.POW, expression);
        try (var arena = new ExprValueArena(4001)) {
            var root = arena.intern(expression);
            assertEquals(4001, arena.size());
            assertEquals(root, arena.intern(expression));
            assertTrue(arena.digest(root).matches("regelsuche.expr-value-digest/v1:[0-9a-f]{64}"));
        }
    }

    @Test void projectedPositionsKeepExactSourceGroupingAndSelectIndependentRewrites() {
        var parsed = parser.parseExactTerm("pair((x + 1.00), (1 + x))");
        var source = (FunctionExpr) parsed.expression();
        try (var arena = new ExprValueArena(4)) {
            var projection = arena.project(source, 7);
            var left = projection.at(new TreePosition(List.of(0), "x + 1")).orElseThrow();
            var right = projection.at(new TreePosition(List.of(1), "1 + x")).orElseThrow();
            assertSame(source, left.source());
            assertSame(source, right.source());
            assertSame(source.arguments().getFirst(), left.syntax());
            assertEquals("(x + 1.00)", parsed.sourceTextFor(left.syntax()).orElseThrow());
            assertEquals("(1 + x)", parsed.sourceTextFor(right.syntax()).orElseThrow());
            assertSame(left.value(), right.value());
            assertNotEquals(left.position(), right.position());
            assertTrue(projection.at(new TreePosition(List.of(2), "missing")).isEmpty());
            assertTrue(projection.at(new TreePosition(List.of(-1), "invalid")).isEmpty());
            var replacement = new VariableExpr("z");
            var changed = (FunctionExpr) right.position().replaceAt(right.source(), replacement)
                .rewrittenRoot().orElseThrow();
            assertSame(left.syntax(), changed.arguments().getFirst());
            assertSame(replacement, changed.arguments().getLast());
            assertSame(right.syntax(), source.arguments().getLast());
            assertThrows(IllegalArgumentException.class, () -> projection.valueOf(parser.parseTerm("x")));
            arena.close();
            assertThrows(IllegalStateException.class, () -> projection.at(left.position()));
        }
    }

    @Test void projectionIndexesPhysicalNodesAndNavigatesOnlyTheRequestedPathInASharedDag() {
        Expr leaf = new VariableExpr("x");
        Expr dag = leaf;
        for (int level = 0; level < 4000; level++)
            dag = new BinaryExpr(dag, BinaryOperator.POW, dag);
        try (var arena = new ExprValueArena(4001)) {
            var projection = arena.project(dag, 4001);
            var first = projection.at(new TreePosition(Collections.nCopies(4000, 0), "x")).orElseThrow();
            var last = projection.at(new TreePosition(Collections.nCopies(4000, 1), "x")).orElseThrow();
            var firstAgain = projection.at(new TreePosition(first.position().path(), "same path")).orElseThrow();
            assertEquals(first, firstAgain);
            assertEquals(first.hashCode(), firstAgain.hashCode());
            assertNotEquals(first, last);
            assertSame(leaf, first.syntax());
            assertSame(first.syntax(), last.syntax());
            assertSame(first.value(), last.value());
            assertNotEquals(first.position(), last.position());
            assertSame(arena.intern(dag), projection.at(new TreePosition(List.of(), "root")).orElseThrow().value());
            var replacement = new VariableExpr("z");
            var rewritten = first.position().replaceAt(first.source(), replacement);
            assertEquals(4000, rewritten.copiedAncestors());
            var changedRoot = rewritten.rewrittenRoot().orElseThrow();
            assertSame(replacement, first.position().subtreeAt(changedRoot).orElseThrow());
            assertSame(leaf, last.position().subtreeAt(changedRoot).orElseThrow());
        }
    }

    @Test void physicallyDistinctEqualSubtreesRespectTheProjectionBoundAndRollback() {
        Expr root = parser.parseTerm("f(x,x,x)");
        try (var arena = new ExprValueArena(3); var other = new ExprValueArena(3)) {
            var existing = arena.intern(new VariableExpr("z"));
            assertThrows(IllegalArgumentException.class, () -> arena.project(root, 0));
            assertThrows(IllegalStateException.class, () -> arena.project(root, 3));
            assertEquals(1, arena.size());
            assertSame(existing, arena.intern(new VariableExpr("z")));
            var projection = arena.project(root, 4);
            var one = projection.at(new TreePosition(List.of(0), "x")).orElseThrow();
            var two = projection.at(new TreePosition(List.of(1), "x")).orElseThrow();
            assertNotSame(one.syntax(), two.syntax());
            assertSame(one.value(), two.value());
            assertThrows(IllegalArgumentException.class, () -> other.digest(one.value()));
            assertEquals(arena.digest(one.value()), other.digest(other.intern(one.syntax())));
        }
    }

    @Test void overflowingAcMultiplicityRollsBackWithoutExpandingTheSharedDag() {
        Expr sum = new VariableExpr("x");
        for (int level = 0; level < 30; level++)
            sum = new BinaryExpr(sum, BinaryOperator.ADD, sum);
        Expr y = new VariableExpr("y");
        for (int level = 0; level < 30; level++)
            y = new BinaryExpr(y, BinaryOperator.ADD, y);
        try (var arena = new ExprValueArena(100)) {
            var valid = arena.intern(sum);
            var oversized = new BinaryExpr(sum, BinaryOperator.ADD, y);
            assertThrows(ArithmeticException.class, () -> arena.intern(oversized));
            assertEquals(31, arena.size());
            assertEquals(valid, arena.intern(sum));
        }
    }

    @Test void aFailedInsertionDoesNotConsumeCapacityOrInvalidateExistingHandles() {
        try (var arena = new ExprValueArena(3)) {
            var x = arena.intern(new VariableExpr("x"));
            var digest = arena.digest(x);
            assertThrows(IllegalStateException.class, () -> arena.intern(parser.parseTerm("f(a,b,c)")));
            assertEquals(1, arena.size());
            assertEquals(x, arena.intern(new VariableExpr("x")));
            arena.intern(parser.parseTerm("f(y)"));
            assertEquals(3, arena.size());
            assertEquals(digest, arena.digest(x));
            arena.close();
            assertThrows(IllegalStateException.class, () -> arena.digest(x));
        }
    }
}
