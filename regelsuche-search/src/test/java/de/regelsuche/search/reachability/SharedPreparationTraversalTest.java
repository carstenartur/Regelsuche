package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class SharedPreparationTraversalTest {
    @Test
    void normalizesAndExpandsOnePhysicalStateOnlyOnce() {
        SharedPreparationTraversal traversal = traversal();

        SharedPreparationTraversal.Expansion first =
            traversal.expand("x+0");
        SharedPreparationTraversal.Expansion second =
            traversal.expand("x + 0");

        assertSame(first, second);
        assertEquals(1, first.transitions().size());
        assertEquals("x", first.transitions().getFirst().target().expression());

        SharedPreparationTraversal.Work work = traversal.work();
        assertEquals(2, work.expansionRequests());
        assertEquals(1, work.uniqueExpansions());
        assertEquals(1, work.expansionCacheHits());
        assertEquals(1, work.generatedTransformations());
        assertEquals(1, work.avoidedExpansionInvocations());
    }

    @Test
    void reusesParsingAcrossEquivalentInputFormatting() {
        SharedPreparationTraversal traversal = traversal();

        SharedPreparationTraversal.ParsedExpression compact =
            traversal.parse("x+0");
        SharedPreparationTraversal.ParsedExpression spaced =
            traversal.parse("x + 0");

        assertSame(compact, spaced);
        assertEquals("x + 0", compact.expression());
        assertEquals(2, traversal.work().parseRequests());
        assertEquals(1, traversal.work().uniqueParsedExpressions());
        assertEquals(1, traversal.work().parseCacheHits());
    }

    @Test
    void sharesPatternAnalysisOnlyForTheSameRecognitionContract() {
        SharedPreparationTraversal traversal = traversal();
        SharedPreparationTraversal.ParsedExpression expression =
            traversal.parse("x + 0");
        PatternExpr pattern = PatternExpr.op(
            BinaryOperator.ADD,
            PatternExpr.var("A"),
            PatternExpr.num(0));

        var first = traversal.analyze(
            pattern,
            RecognitionProfile.exact(),
            expression);
        var second = traversal.analyze(
            pattern,
            RecognitionProfile.exact(),
            expression);
        var ac = traversal.analyze(
            pattern,
            RecognitionProfile.arithmeticAc(),
            expression);

        assertSame(first, second);
        assertNotSame(first, ac);
        assertTrue(first.matched());
        assertTrue(ac.matched());
        assertEquals(3, traversal.work().analysisRequests());
        assertEquals(2, traversal.work().uniqueAnalyses());
        assertEquals(1, traversal.work().analysisCacheHits());
        assertEquals(1, traversal.work().avoidedPatternAnalyses());
    }

    @Test
    void rejectsUnsafePreparationRulesBeforeTheyCanEnterTheSharedCache() {
        PatternRewriteRule unsafe = new PatternRewriteRule(
            "unsafe_remove_zero",
            PatternExpr.op(
                BinaryOperator.ADD,
                PatternExpr.var("A"),
                PatternExpr.num(0)),
            PatternExpr.var("A"),
            RewriteKind.SIMPLIFY,
            false,
            -1,
            false);

        assertThrows(IllegalArgumentException.class, () ->
            new SharedPreparationTraversal(List.of(unsafe), budget()));
    }

    private static SharedPreparationTraversal traversal() {
        PatternRewriteRule removeZero = new PatternRewriteRule(
            "shared_remove_zero",
            PatternExpr.op(
                BinaryOperator.ADD,
                PatternExpr.var("A"),
                PatternExpr.num(0)),
            PatternExpr.var("A"),
            RewriteKind.SIMPLIFY,
            false,
            -1,
            true);
        return new SharedPreparationTraversal(List.of(removeZero), budget());
    }

    private static PatternTargetedLocalBridgeSearch.Budget budget() {
        return new PatternTargetedLocalBridgeSearch.Budget(
            3,
            128,
            1_024,
            8,
            160,
            128,
            32,
            5_000,
            2_500);
    }
}
