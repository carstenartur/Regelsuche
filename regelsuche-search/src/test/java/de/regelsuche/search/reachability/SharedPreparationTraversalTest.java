package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SharedPreparationTraversalTest {
    @Test
    void normalizesAndExpandsOnePhysicalStateOnlyOnce() {
        SharedPreparationTraversal traversal = traversal();
        SharedPreparationTraversal.Expansion first = traversal.expand("x+0");
        SharedPreparationTraversal.Expansion second = traversal.expand("x + 0");
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
        SharedPreparationTraversal.ParsedExpression compact = traversal.parse("x+0");
        SharedPreparationTraversal.ParsedExpression spaced = traversal.parse("x + 0");
        assertSame(compact, spaced);
        assertEquals("x + 0", compact.expression());
        assertEquals(2, traversal.work().parseRequests());
        assertEquals(1, traversal.work().uniqueParsedExpressions());
        assertEquals(1, traversal.work().parseCacheHits());
    }

    @Test
    void sharesPatternAnalysisOnlyForTheSameRecognitionContract() {
        SharedPreparationTraversal traversal = traversal();
        SharedPreparationTraversal.ParsedExpression expression = traversal.parse("x + 0");
        PatternExpr pattern = PatternExpr.op(
            BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.num(0));
        var first = traversal.analyze(pattern, RecognitionProfile.exact(), expression);
        var second = traversal.analyze(pattern, RecognitionProfile.exact(), expression);
        var ac = traversal.analyze(pattern, RecognitionProfile.arithmeticAc(), expression);
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
            PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.num(0)),
            PatternExpr.var("A"), RewriteKind.SIMPLIFY, false, -1, false);
        assertThrows(IllegalArgumentException.class, () ->
            new SharedPreparationTraversal(List.of(unsafe), budget()));
    }

    @Test
    void freezesNearMatchResidualWeights() {
        assertEquals(1, PreparationNearMatchRanking.residualCost(
            PatternMatchAnalyzer.ResidualKind.LITERAL_MISMATCH));
        assertEquals(2, PreparationNearMatchRanking.residualCost(
            PatternMatchAnalyzer.ResidualKind.BINDING_CONFLICT));
        assertEquals(3, PreparationNearMatchRanking.residualCost(
            PatternMatchAnalyzer.ResidualKind.SHAPE_MISMATCH));
        assertEquals(4, PreparationNearMatchRanking.residualCost(
            PatternMatchAnalyzer.ResidualKind.FUNCTION_SHAPE_MISMATCH));
    }

    @Test
    void nearMatchRankPrefersExactMatchAndRetainsLiteralBound() {
        PatternExpr pattern = PatternExpr.op(
            BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.num(0));
        PatternMatchAnalyzer analyzer = new PatternMatchAnalyzer();
        ExpressionParser parser = new ExpressionParser();
        PatternMatchAnalyzer.Analysis exact = analyzer.analyze(
            pattern, parser.parseTerm("x+0"), RecognitionProfile.exact());
        PatternMatchAnalyzer.Analysis near = analyzer.analyze(
            pattern, parser.parseTerm("x+1"), RecognitionProfile.exact());
        assertTrue(exact.matched());
        assertTrue(PreparationNearMatchRanking.rank(exact)
            .compareTo(PreparationNearMatchRanking.rank(near)) < 0);
        assertEquals(1, PreparationNearMatchRanking.residualLowerBound(near));
    }

    @Test
    void nearMatchRankUsesFrozenLexicographicPriority() {
        var unmatched = new PreparationNearMatchRanking.Rank(false, 10, 10, 0, 0);
        var matched = new PreparationNearMatchRanking.Rank(true, 1, 0, 0, 0);
        var morePatternNodes = new PreparationNearMatchRanking.Rank(false, 4, 1, 2, 6);
        var fewerPatternNodes = new PreparationNearMatchRanking.Rank(false, 3, 9, 0, 0);
        var moreBindings = new PreparationNearMatchRanking.Rank(false, 3, 3, 3, 7);
        var fewerBindings = new PreparationNearMatchRanking.Rank(false, 3, 2, 1, 1);
        var fewerResiduals = new PreparationNearMatchRanking.Rank(false, 3, 2, 1, 4);
        var moreResiduals = new PreparationNearMatchRanking.Rank(false, 3, 2, 2, 2);
        var lowerResidualCost = new PreparationNearMatchRanking.Rank(false, 3, 2, 1, 1);
        var higherResidualCost = new PreparationNearMatchRanking.Rank(false, 3, 2, 1, 4);

        assertTrue(matched.compareTo(unmatched) < 0);
        assertTrue(morePatternNodes.compareTo(fewerPatternNodes) < 0);
        assertTrue(moreBindings.compareTo(fewerBindings) < 0);
        assertTrue(fewerResiduals.compareTo(moreResiduals) < 0);
        assertTrue(lowerResidualCost.compareTo(higherResidualCost) < 0);
        List<PreparationNearMatchRanking.Rank> repeated = new ArrayList<>(List.of(
            higherResidualCost, lowerResidualCost, higherResidualCost, lowerResidualCost));
        repeated.sort(null);
        assertEquals(List.of(
            lowerResidualCost, lowerResidualCost, higherResidualCost, higherResidualCost), repeated);
    }

    private static SharedPreparationTraversal traversal() {
        PatternRewriteRule removeZero = new PatternRewriteRule(
            "shared_remove_zero",
            PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.num(0)),
            PatternExpr.var("A"), RewriteKind.SIMPLIFY, false, -1, true);
        return new SharedPreparationTraversal(List.of(removeZero), budget());
    }

    private static PatternTargetedLocalBridgeSearch.Budget budget() {
        return new PatternTargetedLocalBridgeSearch.Budget(
            3, 128, 1_024, 8, 160, 128, 32, 5_000, 2_500);
    }
}
