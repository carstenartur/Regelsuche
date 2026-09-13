package de.regelsuche.value;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.value.ExprValueFactory.ValueOperator.*;

import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import java.util.*;
import org.junit.jupiter.api.Test;

class CompactValueArenaTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test void directScalarConstructionAndAstProjectionIssueTheSameNormalizedValue() {
        try (var arena = new CompactValueArena()) {
            var three = arena.number(ExactRational.integer(3));
            var two = arena.number(ExactRational.integer(2));
            assertSame(arena.project(parser.parseTerm("3 / 2")).value(), arena.ordered(DIV, List.of(three, two)));
            assertSame(arena.project(parser.parseTerm("3 - 2")).value(), arena.ordered(SUB, List.of(three, two)));
            var zero = arena.number(ExactRational.ZERO);
            assertSame(arena.project(parser.parseTerm("3 / 0")).value(), arena.ordered(DIV, List.of(three, zero)));
            assertNotEquals(zero, arena.ordered(DIV, List.of(three, zero)));
        }
    }

    @Test void digestEncodingDoesNotConflateDistinctJavaStringNames() {
        try (var arena = new CompactValueArena()) {
            var first = arena.variable("\uD800");
            var second = arena.variable("\uD801");
            var questionMark = arena.variable("?");
            assertNotEquals(first, second);
            assertNotEquals(arena.stableDigest(first), arena.stableDigest(second));
            assertNotEquals(arena.stableDigest(first), arena.stableDigest(questionMark));
        }
    }

    @Test void exactStructuralCollisionChecksPreserveAcMultiplicityAndOrderedRoles() {
        try (var arena = new CompactValueArena(CompactValueArena.Limits.DEFAULT, 0)) {
            var x = arena.variable("x"); var y = arena.variable("y");
            assertSame(x, arena.variable("x"));
            assertNotEquals(x, y);
            var sum = arena.ordered(ADD, List.of(x, y));
            assertSame(sum, arena.ordered(ADD, List.of(y, x)));
            assertSame(arena.ordered(ADD, List.of(sum, x)), arena.ordered(ADD, List.of(x, y, x)));
            assertNotEquals(sum, arena.ordered(ADD, List.of(x, y, x)));
            assertNotEquals(arena.ordered(DIV, List.of(x, y)), arena.ordered(DIV, List.of(y, x)));
            assertNotEquals(arena.ordered(ExprValueFactory.ValueOperator.function("f", 2), List.of(x, y)),
                arena.ordered(ExprValueFactory.ValueOperator.function("f", 2), List.of(y, x)));
            assertNotEquals(arena.number(ExactRational.parse("9007199254740992")),
                arena.number(ExactRational.parse("9007199254740993")));
        }
    }

    @Test void ownerIdsCannotAuthorizeAnotherOwnerAndDigestsIgnoreAllocationOrder() {
        var syntax = parser.parseTerm("(x + y) * (y + x)");
        try (var first = new CompactValueArena(); var second = new CompactValueArena()) {
            var left = first.project(syntax);
            second.variable("unrelated-allocation"); second.variable("y");
            var right = second.project(parser.parseTerm("(y + x) * (x + y)"));
            assertNotEquals(left.value(), right.value());
            assertThrows(IllegalArgumentException.class, () -> second.stableDigest(left.value()));
            assertThrows(IllegalArgumentException.class, () -> second.ordered(ADD, List.of(left.value(), right.value())));
            assertEquals(0, first.metrics().digestComputations());
            assertEquals(first.stableDigest(left.value()), second.stableDigest(right.value()));
            long computed = first.metrics().digestComputations();
            first.stableDigest(left.value());
            assertEquals(computed, first.metrics().digestComputations());
            assertThrows(IllegalArgumentException.class, () -> left.replace(right.occurrence(List.of()), new VariableExpr("z")));
            first.close();
            assertThrows(IllegalStateException.class, left::value);
            assertThrows(IllegalStateException.class, () -> first.variable("z"));
        }
    }

    @Test void semanticEqualityMatchesTheExistingValueContractAndLegacyExportsRemainExact() {
        var sources = List.of("a + b + a", "a + (a + b)", "b + a", "a + b", "a + a", "a",
            "a * b * a", "b * (a * a)", "a / b", "b / a", "a - b", "b - a", "a ^ b", "b ^ a",
            "f(a, b)", "f(b, a)", "3 - 2", "1", "3 / 2", "1.5", "1 / 0", "0", "x / x - x / x",
            "0 * (1 / 0)", "x + 0", "x", "f(a + b, a + b)", "f(b + a, b + a)");
        try (var arena = new CompactValueArena(); var reference = new ExprValueFactory()) {
            var projections = sources.stream().map(parser::parseTerm).map(arena::project).toList();
            var keys = sources.stream().map(parser::parseTerm).map(reference::fromExpr).map(ExprValueFactory.ExprValue::key).toList();
            for (int i = 0; i < sources.size(); i++) {
                assertEquals(keys.get(i), projections.get(i).legacyKey());
                for (int j = 0; j < sources.size(); j++)
                    assertEquals(keys.get(i).equals(keys.get(j)), projections.get(i).value().equals(projections.get(j).value()), sources.get(i) + " versus " + sources.get(j));
            }
            assertEquals(0, arena.metrics().digestComputations());
        }
    }

    @Test void localReplacementOnlyProjectsCopiedAncestorsAndPreservesDistinctOccurrences() {
        Expr untouched = new VariableExpr("q");
        for (int i = 0; i < 2_000; i++) untouched = new FunctionExpr("f", List.of(untouched));
        var repeated = parser.parseTerm("x + 0");
        var root = new BinaryExpr(repeated, BinaryOperator.MUL, new BinaryExpr(repeated, BinaryOperator.MUL, untouched));
        try (var arena = new CompactValueArena()) {
            var source = arena.project(root);
            var left = source.occurrence(List.of(0)); var right = source.occurrence(List.of(1, 0));
            assertEquals(left.value(), right.value());
            assertNotEquals(left.path(), right.path());
            long before = arena.metrics().syntaxProjections();
            var replacement = ((BinaryExpr) repeated).left();
            var target = source.replace(left, replacement);
            assertEquals(1, arena.metrics().syntaxProjections() - before);
            assertSame(root.right(), ((BinaryExpr) target.syntax()).right());
            assertSame(repeated, target.occurrence(List.of(1, 0)).syntax());
            assertThrows(IllegalArgumentException.class, () -> target.replace(left, replacement));
            assertEquals(0, arena.metrics().digestComputations());
            assertEquals(0, arena.metrics().legacyExports());
        }
    }

    @Test void allRetainedPoolsHaveHardBoundsAndFailedInsertionsDoNotIssueIds() {
        try (var arena = new CompactValueArena(new CompactValueArena.Limits(2, 10, 10, 100))) {
            var x = arena.variable("x"); var y = arena.variable("y");
            assertThrows(CompactValueArena.CapacityExceeded.class, () -> arena.ordered(ADD, List.of(x, y)));
            assertEquals(2, arena.metrics().values());
            assertSame(x, arena.variable("x"));
        }
        try (var arena = new CompactValueArena(new CompactValueArena.Limits(10, 1, 10, 100))) {
            arena.project(new VariableExpr("x"));
            assertThrows(CompactValueArena.CapacityExceeded.class, () -> arena.project(new VariableExpr("x")));
            assertEquals(1, arena.metrics().syntaxNodes());
        }
        try (var arena = new CompactValueArena(new CompactValueArena.Limits(10, 10, 1, 100))) {
            var x = arena.variable("x");
            assertThrows(CompactValueArena.CapacityExceeded.class, () -> arena.ordered(ADD, List.of(x, x)));
            assertEquals(0, arena.metrics().childSlots());
        }
        try (var arena = new CompactValueArena(new CompactValueArena.Limits(10, 10, 10, 1))) {
            assertThrows(CompactValueArena.CapacityExceeded.class, () -> arena.variable("too-large"));
            assertEquals(0, arena.metrics().values());
        }
    }
}
