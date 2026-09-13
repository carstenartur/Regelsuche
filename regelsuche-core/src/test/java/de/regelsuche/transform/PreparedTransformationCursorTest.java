package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PreparedTransformationCursorTest {
    private static final RewriteRule ADD_ZERO = AstRewriteTransformationEngine.defaultRules().stream()
        .filter(rule -> rule.id().equals("ast_add_zero_right")).findFirst().orElseThrow();
    private static final RewriteRule MUL_ONE = AstRewriteTransformationEngine.defaultRules().stream()
        .filter(rule -> rule.id().equals("ast_multiply_one_right")).findFirst().orElseThrow();

    @ParameterizedTest @ValueSource(strings = {"((a + 0) + (b + 0)) + 0", "sin((x + 0) * 1)",
        "f(a + 0, b * 1, (c + 0) * 1)", "(x + 0) + (x + 0)", "x * 1 + y + 0", "x"})
    void fullDrainHasExactRawPreparedOrder(String source) {
        var engine = new PreparedAstRewriteTransformationEngine(List.of(MUL_ONE, ADD_ZERO), 12, 100);
        try (var cursor = engine.openCursor(source)) {
            assertEquals(engine.transform(source), drain(cursor));
            assertTrue(cursor.snapshot().complete());
            assertEquals(TransformationCursor.Status.EXHAUSTED, cursor.snapshot().status());
        }
    }

    @Test void onePullDoesNotVisitOrMatchInnerOccurrencesAndCloseDoesNotDrain() {
        var engine = new PreparedAstRewriteTransformationEngine(List.of(ADD_ZERO), 12, 100);
        var cursor = engine.openCursor("((a + 0) + (b + 0)) + 0");
        assertEquals(0, cursor.work().units(TransformationCursor.Operation.PARSE));
        assertEquals(engine.transform("((a + 0) + (b + 0)) + 0").getFirst(), cursor.next(Long.MAX_VALUE).orElseThrow());
        assertEquals(1, cursor.snapshot().attempts().size());
        assertEquals(1, cursor.work().units(TransformationCursor.Operation.OCCURRENCE_VISIT));
        cursor.close();
        var snapshot = cursor.snapshot();
        cursor.close();
        assertEquals(snapshot, cursor.snapshot());
        assertTrue(cursor.next(Long.MAX_VALUE).isEmpty());
        assertEquals(1, cursor.work().primitiveRewrites());
        assertEquals(1, cursor.work().units(TransformationCursor.Operation.CLOSE));
        assertFalse(snapshot.complete());
    }

    @Test void unsuccessfulMatchesAndNoChangeAreRetained() {
        var engine = new PreparedAstRewriteTransformationEngine(List.of(ADD_ZERO));
        try (var cursor = engine.openCursor("a + b")) {
            assertTrue(drain(cursor).isEmpty());
            assertEquals(3, cursor.snapshot().attempts().size());
            assertTrue(cursor.snapshot().attempts().stream().allMatch(attempt -> attempt.outcome() == TransformationCursor.AttemptOutcome.NOT_MATCHED));
            assertEquals(0, cursor.work().primitiveRewrites());
            assertTrue(cursor.work().mechanicalUnits() > 0);
        }
        var unchanged = new PatternRewriteRule("identity", PatternExpr.var("A"), PatternExpr.var("A"));
        try (var cursor = new PreparedAstRewriteTransformationEngine(List.of(unchanged)).openCursor("a")) {
            assertTrue(drain(cursor).isEmpty());
            assertEquals(TransformationCursor.AttemptOutcome.UNCHANGED, cursor.snapshot().attempts().getFirst().outcome());
            assertEquals(1, cursor.work().units(TransformationCursor.Operation.INSTANTIATE));
            assertEquals(0, cursor.work().primitiveRewrites());
        }
    }

    @Test void duplicateAndGrowthFiltersKeepActualRewriteWorkAndCapsDoNotClaimExhaustion() {
        var duplicates = new PreparedAstRewriteTransformationEngine(List.of(ADD_ZERO, ADD_ZERO), 12, 100);
        try (var cursor = duplicates.openCursor("a + 0")) {
            assertEquals(duplicates.transform("a + 0"), drain(cursor));
            assertEquals(2, cursor.work().primitiveRewrites());
            assertEquals(1, cursor.snapshot().attempts().stream().filter(attempt -> attempt.outcome() == TransformationCursor.AttemptOutcome.DUPLICATE).count());
        }
        var grow = new PatternRewriteRule("grow", PatternExpr.var("A"), PatternExpr.op(ADD, PatternExpr.var("A"), PatternExpr.num(1)));
        try (var cursor = new PreparedAstRewriteTransformationEngine(List.of(grow), 0, 100).openCursor("a")) {
            assertTrue(drain(cursor).isEmpty());
            assertEquals(1, cursor.work().primitiveRewrites());
            assertEquals(TransformationCursor.AttemptOutcome.GROWTH_REJECTED, cursor.snapshot().attempts().getFirst().outcome());
        }
        var capped = new PreparedAstRewriteTransformationEngine(List.of(ADD_ZERO), 12, 1);
        try (var cursor = capped.openCursor("(a + 0) + 0")) {
            assertEquals(capped.transform("(a + 0) + 0"), drain(cursor));
            assertEquals(TransformationCursor.Status.CANDIDATE_LIMIT, cursor.snapshot().status());
            assertFalse(cursor.snapshot().complete());
            assertEquals(1, cursor.snapshot().attempts().size());
        }
    }

    @Test void setupOverrunInvalidInputAndInstantiationFailureKeepTheirEvidence() {
        var engine = new PreparedAstRewriteTransformationEngine(List.of(ADD_ZERO));
        try (var cursor = engine.openCursor("a + 0")) {
            assertTrue(cursor.next(1).isEmpty());
            assertEquals(TransformationCursor.Status.WORK_EXHAUSTED, cursor.snapshot().status());
            assertEquals(1, cursor.work().units(TransformationCursor.Operation.PARSE));
            assertTrue(cursor.work().totalUnits() > 1);
            assertTrue(cursor.snapshot().attempts().isEmpty());
        }
        try (var cursor = engine.openCursor("(")) {
            assertTrue(drain(cursor).isEmpty());
            assertEquals(TransformationCursor.Status.INVALID_INPUT, cursor.snapshot().status());
            assertEquals(1, cursor.work().units(TransformationCursor.Operation.PARSE));
            assertFalse(cursor.snapshot().complete());
        }
        var invalid = new PatternRewriteRule("unbound-target", PatternExpr.var("A"), PatternExpr.var("B"));
        try (var cursor = new PreparedAstRewriteTransformationEngine(List.of(invalid)).openCursor("a")) {
            assertTrue(drain(cursor).isEmpty());
            assertEquals(TransformationCursor.Status.FAILED, cursor.snapshot().status());
            assertEquals(TransformationCursor.AttemptOutcome.FAILED, cursor.snapshot().attempts().getFirst().outcome());
            assertEquals(1, cursor.work().units(TransformationCursor.Operation.INSTANTIATE));
        }
    }

    @Test void matcherInconclusiveIsAnObservedLimitNotANegativeCompleteMatch() {
        var ac = new PatternRewriteRule("commutative-limit", PatternExpr.op(ADD, PatternExpr.variable("a"), PatternExpr.variable("b")),
            PatternExpr.variable("a"), RecognitionProfile.arithmeticAc());
        try (var cursor = new PreparedAstRewriteTransformationEngine(List.of(ac)).openCursor("b + a", 1)) {
            drain(cursor);
            assertTrue(cursor.snapshot().attempts().stream().anyMatch(attempt -> attempt.outcome() == TransformationCursor.AttemptOutcome.MATCH_INCONCLUSIVE));
            assertTrue(cursor.work().units(TransformationCursor.Operation.MATCHER_BRANCH) > 0);
            assertFalse(cursor.snapshot().complete());
        }
    }

    @Test void customRuleDispatchIsRejectedBeforeOpening() {
        var custom = new PatternRewriteRule("custom", PatternExpr.var("A"), PatternExpr.var("A")) {};
        var engine = new PreparedAstRewriteTransformationEngine(List.of(custom));
        assertThrows(IllegalArgumentException.class, () -> engine.openCursor("a"));
    }

    @Test void frozenDefinitionsDistinguishPatternsWhoseDisplayStringsCollide() {
        var first = PatternExpr.op(ADD, PatternExpr.variable("a], right=LiteralVariable[name=b"), PatternExpr.variable("c"));
        var second = PatternExpr.op(ADD, PatternExpr.variable("a"), PatternExpr.variable("b], right=LiteralVariable[name=c"));
        assertNotEquals(first, second);
        assertEquals(first.toString(), second.toString(), "display strings are not an authoritative structural encoding");
        var firstRule = new PatternRewriteRule("collision", first, PatternExpr.num(0));
        var secondRule = new PatternRewriteRule("collision", second, PatternExpr.num(0));
        assertNotEquals(new PreparedAstRewriteTransformationEngine(List.of(firstRule)).cursorDefinition(100),
            new PreparedAstRewriteTransformationEngine(List.of(secondRule)).cursorDefinition(100));
    }

    private static List<Transformation> drain(TransformationCursor cursor) {
        var values = new ArrayList<Transformation>();
        for (var next = cursor.next(Long.MAX_VALUE); next.isPresent(); next = cursor.next(Long.MAX_VALUE)) values.add(next.orElseThrow());
        return values;
    }
}
