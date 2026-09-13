package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.value.CompactValueArena;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Overlapping opt-in consumers keep their own source, issuer, work and lifetime. */
class PreparedNativeOptInIntegrationReviewTest {
    private static final String SOURCE = "(x + 0) * (x + 0)";
    private static final RewriteRule ADD_ZERO = AstRewriteTransformationEngine.defaultRules().stream()
        .filter(rule -> rule.id().equals("ast_add_zero_right")).findFirst().orElseThrow();
    private static final AssumptionSignature EMPTY = AssumptionSignature.ofExpressions(List.of());

    @Test void interleavedSessionsLeaveBothCursorTracesAndHistoricalOutputsUnchanged() {
        var engine = new PreparedAstRewriteTransformationEngine(List.of(ADD_ZERO));
        var expected = engine.transform(SOURCE);
        assertEquals(2, expected.size());
        var syntax = new ExpressionParser().parseTerm(SOURCE);
        var assumed = AssumptionSignature.ofExpressions(List.of("x != 0"));
        var localBudget = new NativeValueRewriteSession.Budget(2, 2, 2, 100);
        try (var isolatedOld = engine.openCursor(SOURCE); var isolatedShape = engine.openShapeIndexedCursor(SOURCE)) {
            assertEquals(expected, drain(isolatedOld));
            assertEquals(expected, drain(isolatedShape));
            try (var old = engine.openCursor(SOURCE); var shape = engine.openShapeIndexedCursor(SOURCE);
                 var firstArena = new CompactValueArena(); var secondArena = new CompactValueArena();
                 var first = engine.openValueSession(firstArena.project(syntax), EMPTY, localBudget);
                 var second = engine.openValueSession(secondArena.project(syntax), assumed, localBudget)) {
                assertEquals(expected.getFirst(), old.next(Long.MAX_VALUE).orElseThrow());
                assertEquals(expected.getFirst(), shape.next(Long.MAX_VALUE).orElseThrow());
                var oldPrefix = old.snapshot();
                var shapePrefix = shape.indexReceipt();
                var secondSource = second.current();
                var secondWork = second.work();
                assertNotEquals(first.current().value(), secondSource.value(), "one engine does not merge arena owners");
                var left = first.current().occurrence(List.of(0));
                assertThrows(IllegalArgumentException.class, () -> second.apply(left, 0));
                assertEquals(secondWork, second.work(), "foreign occurrences are rejected before admission");

                var firstStep = first.apply(left, 0);
                assertEquals(NativeValueRewriteSession.Status.APPLIED, firstStep.status());
                assertEquals(expected.getFirst(), first.exportLegacy(firstStep));
                assertThrows(IllegalArgumentException.class, () -> second.exportLegacy(firstStep));
                var secondStep = first.apply(first.current().occurrence(List.of(1)), 0);
                assertEquals("x * x", first.exportLegacy(secondStep).transformedExpression());
                assertEquals(new NativeValueRewriteSession.Work(2, 0, 2, 2, 2, 2), first.work());
                assertSame(secondSource, second.current());
                assertEquals(secondWork, second.work());
                assertEquals(EMPTY, first.assumptions());
                assertEquals(assumed, second.assumptions());
                assertEquals(oldPrefix, old.snapshot());
                assertEquals(shapePrefix, shape.indexReceipt());

                first.close();
                firstArena.close();
                var opposite = second.apply(second.current().occurrence(List.of(1)), 0);
                assertEquals(expected.get(1), second.exportLegacy(opposite));
                assertEquals(List.of(expected.get(1)), drain(old));
                assertEquals(List.of(expected.get(1)), drain(shape));
                assertEquals(isolatedOld.snapshot(), old.snapshot(), "old work and attempts retain the original source");
                assertEquals(isolatedShape.indexReceipt(), shape.indexReceipt());
                assertEquals(TransformationCursor.WORK_REVISION, old.snapshot().workRevision());
                assertEquals(ShapeIndexedTransformationCursor.WORK_REVISION, shape.snapshot().workRevision());
                assertEquals(TransformationCursor.ORDER_REVISION, old.snapshot().definition().orderRevision());
                assertEquals(1, oldPrefix.emittedCandidates(), "retained observations are immutable prefixes");
                assertEquals(1, shapePrefix.cursor().emittedCandidates());
                assertEquals(new NativeValueRewriteSession.Work(1, 0, 1, 1, 1, 0), firstStep.work());
            }
        }
        assertEquals(expected, engine.transform(SOURCE));
    }

    @Test void exhaustedAndClosedConsumersDoNotSpendOrResetTheirNeighboursBudgets() {
        var engine = new PreparedAstRewriteTransformationEngine(List.of(ADD_ZERO));
        try (var arena = new CompactValueArena(); var shape = engine.openShapeIndexedCursor(SOURCE);
             var old = engine.openCursor(SOURCE);
             var local = engine.openValueSession(arena.project(new ExpressionParser().parseTerm(SOURCE)), EMPTY,
                 new NativeValueRewriteSession.Budget(1, 1, 1, 100))) {
            var untouchedOld = old.snapshot();
            assertTrue(shape.next(0).isEmpty());
            assertEquals(TransformationCursor.Status.WORK_EXHAUSTED, shape.snapshot().status());
            shape.close();
            var exhausted = shape.indexReceipt();
            assertEquals(new NativeValueRewriteSession.Work(0, 0, 0, 0, 0, 0), local.work());
            assertEquals(untouchedOld, old.snapshot());
            var applied = local.apply(local.current().occurrence(List.of(0)), 0);
            assertEquals(NativeValueRewriteSession.Status.APPLIED, applied.status());
            assertEquals(applied.work(), local.work());
            assertEquals(NativeValueRewriteSession.Status.BUDGET_EXHAUSTED,
                local.apply(local.current().occurrence(List.of(1)), 0).status());
            assertEquals(applied.work(), local.work());
            assertEquals(exhausted, shape.indexReceipt());
            assertEquals(engine.transform(SOURCE), drain(old));
            assertEquals(applied.work(), local.work());
            assertTrue(old.snapshot().complete());
            assertTrue(shape.next(Long.MAX_VALUE).isEmpty());
            assertEquals(exhausted, shape.indexReceipt(), "other native work cannot revive a spent cursor");
            local.close();
            try (var fresh = engine.openShapeIndexedCursor(SOURCE)) {
                assertEquals(engine.transform(SOURCE), drain(fresh));
                assertTrue(fresh.snapshot().complete());
            }
        }
    }

    private static List<Transformation> drain(TransformationCursor cursor) {
        var result = new ArrayList<Transformation>();
        for (var next = cursor.next(Long.MAX_VALUE); next.isPresent(); next = cursor.next(Long.MAX_VALUE))
            result.add(next.orElseThrow());
        return result;
    }
}
