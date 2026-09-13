package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.value.CompactValueArena;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeValueRewriteSessionReviewTest {
    private final RewriteRule addZero = AstRewriteTransformationEngine.defaultRules().stream()
        .filter(rule -> rule.id().equals("ast_add_zero_right")).findFirst().orElseThrow();
    private final PreparedAstRewriteTransformationEngine engine = new PreparedAstRewriteTransformationEngine(List.of(addZero));
    private final AssumptionSignature assumptions = AssumptionSignature.ofExpressions(List.of());

    @Test void failedRootAdmissionRetainsActualCopiesAndPartialInternsWithoutAdvancingTheSession() {
        try (var arena = new CompactValueArena(new CompactValueArena.Limits(6, 20, 30, 1000))) {
            var source = arena.project(new ExpressionParser().parseTerm("f(g(x+0))"));
            assertEquals(5, arena.metrics().values());
            try (var session = engine.openValueSession(source, assumptions, new NativeValueRewriteSession.Budget(2, 1, 2, 100))) {
                var occurrence = source.occurrence(List.of(0, 0));
                var attempt = session.apply(occurrence, 0);
                assertEquals(NativeValueRewriteSession.Status.ARENA_EXHAUSTED, attempt.status());
                assertSame(source, session.current());
                assertSame(source, attempt.source());
                assertTrue(attempt.target().isEmpty());
                assertEquals(new NativeValueRewriteSession.Work(1, 0, 1, 1, 2, 0), attempt.work());
                assertEquals(6, arena.metrics().values(), "g(x) was admitted before the copied root exhausted capacity");
                assertEquals(6, arena.metrics().syntaxProjections());
                assertEquals(0, arena.metrics().digestComputations());
                assertThrows(IllegalArgumentException.class, () -> session.exportLegacy(attempt));
                assertEquals(NativeValueRewriteSession.Status.BUDGET_EXHAUSTED, session.apply(occurrence, 0).status());
                assertEquals(attempt.work(), session.work(), "failed candidate work is not refunded for another attempt");
                assertSame(source, session.current());
            }
            assertNotNull(source.value(), "closing the session preserves its caller-owned arena");
        }
    }

    @Test void spentAncestorBudgetStillAllowsRootRewriteAndTheExportedChainReplaysAgainstFreshNativeWork() {
        try (var arena = new CompactValueArena();
             var session = engine.openValueSession(arena.project(new ExpressionParser().parseTerm("(x+0)+0")),
                 assumptions, new NativeValueRewriteSession.Budget(3, 2, 1, 100))) {
            String source = ExpressionFormatter.format(session.current().syntax());
            var first = session.apply(session.current().occurrence(List.of(0)), 0);
            assertEquals(NativeValueRewriteSession.Status.APPLIED, first.status());
            assertEquals(1, first.work().ancestorCopies());
            var beforeRefusal = session.work();
            assertEquals(NativeValueRewriteSession.Status.BUDGET_EXHAUSTED,
                session.apply(session.current().occurrence(List.of(1)), 0).status());
            assertEquals(beforeRefusal, session.work(), "a disallowed nonroot attempt never enters the matcher");
            var second = session.apply(session.current().occurrence(List.of()), 0);
            assertEquals(NativeValueRewriteSession.Status.APPLIED, second.status());
            assertEquals(new NativeValueRewriteSession.Work(2, 0, 2, 2, 1, 0), session.work());
            assertEquals(0, arena.metrics().digestComputations());

            var firstExport = session.exportLegacy(first);
            var secondExport = session.exportLegacy(second);
            var retained = RecordedExecution.fromCanonicalJson(
                RecordedExecution.capture(source, List.of(firstExport, secondExport)).toCanonicalJson());
            var freshFirst = engine.transform(source).stream().filter(firstExport::equals).findFirst().orElseThrow();
            var freshSecond = engine.transform(freshFirst.transformedExpression()).stream()
                .filter(secondExport::equals).findFirst().orElseThrow();
            retained.requireReplay(source, List.of(freshFirst, freshSecond));
            assertEquals("x", retained.transformedExpression());
            assertEquals(2, retained.work().primitiveRewrites());
            assertThrows(IllegalArgumentException.class, () -> retained.requireReplay(source, List.of(freshFirst)));
        }
    }
}
