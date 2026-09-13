package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.*;
import de.regelsuche.parse.*;
import de.regelsuche.value.CompactValueArena;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeValueRewriteSessionTest {
    private static final RewriteRule ADD_ZERO = AstRewriteTransformationEngine.defaultRules().stream()
        .filter(rule -> rule.id().equals("ast_add_zero_right")).findFirst().orElseThrow();
    private final ExpressionParser parser = new ExpressionParser();
    private final AssumptionSignature empty = AssumptionSignature.ofExpressions(List.of());
    private final NativeValueRewriteSession.Budget budget = new NativeValueRewriteSession.Budget(20, 10, 100, 100);

    @Test void ancestorBudgetRejectsBeforeNativeWorkAndClosedSessionCannotExecute() {
        try (var arena = new CompactValueArena(); var session = new PreparedAstRewriteTransformationEngine(List.of(ADD_ZERO))
                .openValueSession(arena.project(parser.parseTerm("f(x + 0)")), empty,
                    new NativeValueRewriteSession.Budget(2, 2, 0, 100))) {
            var source = session.current();
            var occurrence = source.occurrence(List.of(0));
            assertEquals(NativeValueRewriteSession.Status.BUDGET_EXHAUSTED, session.apply(occurrence, 0).status());
            assertEquals(new NativeValueRewriteSession.Work(0, 0, 0, 0, 0, 0), session.work());
            assertSame(source, session.current());
            session.close();
            assertThrows(IllegalStateException.class, () -> session.apply(occurrence, 0));
        }
    }

    @Test void realLocalNativeRewriteCarriesAstAndReusesSiblingValuesUntilExplicitLegacyExport() {
        var engine = new PreparedAstRewriteTransformationEngine(List.of(ADD_ZERO));
        var root = parser.parseTerm("(x + 0) * (x + 0)");
        try (var arena = new CompactValueArena(); var session = engine.openValueSession(arena.project(root), empty, budget)) {
            var occurrence = session.current().occurrence(List.of(0));
            long projected = arena.metrics().syntaxProjections();
            var result = session.apply(occurrence, 0);
            assertEquals(NativeValueRewriteSession.Status.APPLIED, result.status());
            assertSame(((BinaryExpr) root).right(), ((BinaryExpr) session.current().syntax()).right());
            assertEquals(1, arena.metrics().syntaxProjections() - projected);
            assertEquals(0, arena.metrics().digestComputations());
            assertEquals(0, session.work().legacyExports());
            var legacy = session.exportLegacy(result);
            assertTrue(engine.transform(ExpressionFormatter.format(root)).contains(legacy));
            assertEquals("x * (x + 0)", legacy.transformedExpression());
            assertEquals(1, session.work().primitiveRewrites());
            assertEquals(1, session.work().ancestorCopies());
            assertEquals(1, session.work().legacyExports());
            assertThrows(IllegalArgumentException.class, () -> session.apply(occurrence, 0));
            var second = session.apply(session.current().occurrence(List.of(1)), 0);
            assertEquals("x * x", session.exportLegacy(second).transformedExpression());
            assertEquals(2, session.work().primitiveRewrites());
        }
    }

    @Test void equalValuesDoNotEraseSyntaxChangesAssumptionsOrSeparateSessionBudgets() {
        var swap = new PatternRewriteRule("swap", PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.var("B")),
            PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("B"), PatternExpr.var("A")));
        var engine = new PreparedAstRewriteTransformationEngine(List.of(swap));
        var assumed = AssumptionSignature.ofExpressions(List.of("x != 0"));
        try (var arena = new CompactValueArena()) {
            var projection = arena.project(parser.parseTerm("x + y"));
            try (var first = engine.openValueSession(projection, empty, budget);
                 var second = engine.openValueSession(projection, assumed, new NativeValueRewriteSession.Budget(1, 0, 0, 1))) {
                var result = first.apply(first.current().occurrence(List.of()), 0);
                assertEquals(NativeValueRewriteSession.Status.APPLIED, result.status());
                assertEquals(projection.value(), first.current().value());
                assertEquals("y + x", first.exportLegacy(result).transformedExpression());
                assertEquals(empty, first.assumptions()); assertEquals(assumed, second.assumptions());
                assertSame(projection, second.current());
                assertEquals(NativeValueRewriteSession.Status.BUDGET_EXHAUSTED,
                    second.apply(second.current().occurrence(List.of()), 0).status());
                assertEquals(0, second.work().instantiations());
                assertThrows(IllegalArgumentException.class, () -> second.exportLegacy(result));
            }
        }
    }

    @Test void failedMatchingInstantiationAndArenaAdmissionRetainActualWorkWithoutChangingCurrentRoot() {
        var unbound = new PatternRewriteRule("unbound", PatternExpr.var("A"), PatternExpr.var("B"));
        var engine = new PreparedAstRewriteTransformationEngine(List.of(ADD_ZERO, unbound));
        try (var arena = new CompactValueArena(); var session = engine.openValueSession(arena.project(parser.parseTerm("x")), empty, budget)) {
            var source = session.current();
            assertEquals(NativeValueRewriteSession.Status.NOT_MATCHED, session.apply(source.occurrence(List.of()), 0).status());
            assertEquals(NativeValueRewriteSession.Status.FAILED, session.apply(source.occurrence(List.of()), 1).status());
            assertSame(source, session.current());
            assertEquals(2, session.work().attempts()); assertEquals(1, session.work().instantiations());
            assertEquals(0, session.work().primitiveRewrites());
        }
        var grow = new PatternRewriteRule("grow", PatternExpr.var("A"), PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.num(0)));
        try (var arena = new CompactValueArena(new CompactValueArena.Limits(1, 10, 10, 100))) {
            var source = arena.project(parser.parseTerm("x"));
            try (var session = new PreparedAstRewriteTransformationEngine(List.of(grow)).openValueSession(source, empty, budget)) {
                var result = session.apply(source.occurrence(List.of()), 0);
                assertEquals(NativeValueRewriteSession.Status.ARENA_EXHAUSTED, result.status());
                assertSame(source, session.current());
                assertEquals(1, session.work().primitiveRewrites());
                assertThrows(IllegalArgumentException.class, () -> session.exportLegacy(result));
            }
        }
    }

    @Test void nativeBoundedMatchingReportsInconclusiveAndRejectsCustomDispatchBeforeExecution() {
        var ac = new PatternRewriteRule("ac", PatternExpr.op(BinaryOperator.ADD, PatternExpr.variable("a"), PatternExpr.variable("b")),
            PatternExpr.variable("a"), RecognitionProfile.arithmeticAc());
        try (var arena = new CompactValueArena(); var session = new PreparedAstRewriteTransformationEngine(List.of(ac))
                .openValueSession(arena.project(parser.parseTerm("b + a")), empty, new NativeValueRewriteSession.Budget(1, 1, 0, 1))) {
            var source = session.current();
            assertEquals(NativeValueRewriteSession.Status.MATCH_INCONCLUSIVE, session.apply(source.occurrence(List.of()), 0).status());
            assertTrue(session.work().matcherBranches() > 0);
            assertSame(source, session.current());
            assertEquals(NativeValueRewriteSession.Status.BUDGET_EXHAUSTED, session.apply(source.occurrence(List.of()), 0).status());
        }
        try (var arena = new CompactValueArena()) {
            var custom = new PatternRewriteRule("custom", PatternExpr.var("A"), PatternExpr.var("A")) {};
            assertThrows(IllegalArgumentException.class, () -> new PreparedAstRewriteTransformationEngine(List.of(custom))
                .openValueSession(arena.project(parser.parseTerm("x")), empty, budget));
        }
    }
}
