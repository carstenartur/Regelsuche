package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Independent controls for interrupted selection and limits below a selected root. */
class ShapeIndexedCursorReviewTest {
    private static final PatternExpr A = PatternExpr.var("A");

    @Test void warmChildRejectionRetainsItsOverrunAndCannotResumeOrResetThePrefix() {
        var outer = new PatternRewriteRule("outer", PatternExpr.op(ADD, A, PatternExpr.num(0)), A);
        var rejected = new PatternRewriteRule("child-rejected",
            PatternExpr.op(ADD, PatternExpr.fn("f", A), PatternExpr.var("B")), PatternExpr.num(2));
        var laterFailure = new PatternRewriteRule("later-failure", A, PatternExpr.var("MISSING"));
        var engine = new PreparedAstRewriteTransformationEngine(List.of(outer, rejected, laterFailure));
        for (int allowance : List.of(1, 2, 3)) {
            var cursor = engine.openShapeIndexedCursor("(x+0)+0");
            try (cursor) {
                assertEquals("outer", cursor.next(Long.MAX_VALUE).orElseThrow().rule());
                var first = cursor.indexReceipt();
                long before = cursor.work().totalUnits();

                assertTrue(cursor.next(allowance).isEmpty());
                var exhausted = cursor.indexReceipt();
                assertEquals(before + 3, exhausted.cursor().work().totalUnits(),
                    "one selection, one new child feature and one rejecting predicate actually ran");
                assertEquals(TransformationCursor.Status.WORK_EXHAUSTED, exhausted.cursor().status());
                assertFalse(exhausted.cursor().complete());
                assertEquals(1, exhausted.cursor().work().units(TransformationCursor.Operation.RULE_MATCH));
                assertEquals(1, exhausted.cursor().work().units(TransformationCursor.Operation.INSTANTIATE));
                assertEquals(1, exhausted.cursor().work().primitiveRewrites());
                assertEquals(1, exhausted.selections().size(), "no descendant occurrence was entered");
                assertEquals(List.of(TransformationCursor.AttemptOutcome.EMITTED,
                        TransformationCursor.AttemptOutcome.SHAPE_REJECTED),
                    exhausted.cursor().attempts().stream().map(TransformationCursor.Attempt::outcome).toList());
                assertEquals(1, first.cursor().attempts().size(), "earlier receipts retain their own prefix");
                assertEquals(TransformationCursor.Status.READY, first.cursor().status());
                assertTrue(cursor.next(Long.MAX_VALUE).isEmpty());
                assertEquals(exhausted, cursor.indexReceipt(), "a later allowance cannot revive the exhausted cursor");
            }
            var closed = cursor.indexReceipt();
            assertEquals(1, closed.cursor().work().units(TransformationCursor.Operation.CLOSE));
            assertEquals(TransformationCursor.Status.WORK_EXHAUSTED, closed.cursor().status());
            cursor.close();
            assertEquals(closed, cursor.indexReceipt());
        }
    }

    @Test void nestedCommutativeLimitSurvivesALaterImpossibleChildConstraint() {
        PatternExpr sum = PatternExpr.var("V0");
        for (int index = 1; index < 9; index++) sum = PatternExpr.op(ADD, sum, PatternExpr.var("V" + index));
        var rule = new PatternRewriteRule("nested-ac-limit", PatternExpr.fn("f", sum, PatternExpr.num(0)),
            PatternExpr.num(7), RecognitionProfile.arithmeticAc());
        assertRootLimitIsRetained(rule, "f(a+b+c+d+e+f+g+h+i,x)", "COMMUTATIVE_OPERAND_LIMIT");
    }

    @Test void algebraicCoefficientLimitSurvivesADifferentLiteralPatternRoot() {
        var rule = new PatternRewriteRule("literal-algebraic-limit", PatternExpr.num(0), PatternExpr.num(7),
            RecognitionProfile.algebraicAc());
        assertRootLimitIsRetained(rule, "2^4097", "ALGEBRAIC_COEFFICIENT_LIMIT");
    }

    private static void assertRootLimitIsRetained(PatternRewriteRule rule, String source, String limitCode) {
        var full = EquivalenceAwarePatternMatcher.matchDetailed(rule.source(), new ExpressionParser().parseTerm(source),
            new HashMap<>(), rule.recognitionProfile(), TransformationCursor.DEFAULT_MATCHER_BRANCH_LIMIT);
        assertTrue(full.inconclusive(), "the production matcher itself must reach the limit");
        assertEquals(limitCode, full.limitCode());
        var engine = new PreparedAstRewriteTransformationEngine(List.of(rule));
        try (var reference = engine.openCursor(source); var indexed = engine.openShapeIndexedCursor(source)) {
            assertEquals(drain(reference), drain(indexed));
            var root = indexed.snapshot().attempts().getFirst();
            assertTrue(root.path().isEmpty());
            assertEquals(reference.snapshot().attempts().getFirst(), root);
            assertEquals(TransformationCursor.AttemptOutcome.MATCH_INCONCLUSIVE, root.outcome());
            assertEquals(limitCode, root.detailCode());
            assertFalse(reference.snapshot().complete());
            assertFalse(indexed.snapshot().complete());
            assertEquals(TransformationCursor.Status.EXHAUSTED, indexed.snapshot().status());
            assertEquals(0, indexed.work().units(TransformationCursor.Operation.SHAPE_PREDICATE_CHECK));
            assertEquals(reference.work().units(TransformationCursor.Operation.MATCHER_BRANCH),
                indexed.work().units(TransformationCursor.Operation.MATCHER_BRANCH));
        }
    }

    private static List<Transformation> drain(TransformationCursor cursor) {
        var result = new ArrayList<Transformation>();
        for (var next = cursor.next(Long.MAX_VALUE); next.isPresent(); next = cursor.next(Long.MAX_VALUE))
            result.add(next.orElseThrow());
        return result;
    }
}
