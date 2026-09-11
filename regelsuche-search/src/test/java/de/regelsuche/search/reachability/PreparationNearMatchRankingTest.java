package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.RecognitionProfile;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreparationNearMatchRankingTest {
    @Test
    void freezesResidualLowerBoundWeights() {
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
    void ranksExactMatchBeforeNearMatchAndRetainsLiteralLowerBound() {
        PatternExpr pattern = PatternExpr.op(
            BinaryOperator.ADD,
            PatternExpr.var("A"),
            PatternExpr.num(0));
        PatternMatchAnalyzer analyzer = new PatternMatchAnalyzer();
        ExpressionParser parser = new ExpressionParser();
        PatternMatchAnalyzer.Analysis exact = analyzer.analyze(
            pattern,
            parser.parseTerm("x+0"),
            RecognitionProfile.exact());
        PatternMatchAnalyzer.Analysis near = analyzer.analyze(
            pattern,
            parser.parseTerm("x+1"),
            RecognitionProfile.exact());

        assertTrue(exact.matched());
        assertTrue(PreparationNearMatchRanking.rank(exact)
            .compareTo(PreparationNearMatchRanking.rank(near)) < 0);
        assertEquals(1, PreparationNearMatchRanking.residualLowerBound(near));
    }

    @Test
    void rankUsesTheFrozenLexicographicPriority() {
        PreparationNearMatchRanking.Rank unmatched =
            new PreparationNearMatchRanking.Rank(false, 10, 10, 0, 0);
        PreparationNearMatchRanking.Rank matched =
            new PreparationNearMatchRanking.Rank(true, 1, 0, 0, 0);
        PreparationNearMatchRanking.Rank morePatternNodes =
            new PreparationNearMatchRanking.Rank(false, 4, 1, 2, 6);
        PreparationNearMatchRanking.Rank fewerPatternNodes =
            new PreparationNearMatchRanking.Rank(false, 3, 9, 0, 0);
        PreparationNearMatchRanking.Rank moreBindings =
            new PreparationNearMatchRanking.Rank(false, 3, 3, 3, 7);
        PreparationNearMatchRanking.Rank fewerBindings =
            new PreparationNearMatchRanking.Rank(false, 3, 2, 1, 1);
        PreparationNearMatchRanking.Rank fewerResiduals =
            new PreparationNearMatchRanking.Rank(false, 3, 2, 1, 4);
        PreparationNearMatchRanking.Rank moreResiduals =
            new PreparationNearMatchRanking.Rank(false, 3, 2, 2, 2);
        PreparationNearMatchRanking.Rank lowerResidualCost =
            new PreparationNearMatchRanking.Rank(false, 3, 2, 1, 1);
        PreparationNearMatchRanking.Rank higherResidualCost =
            new PreparationNearMatchRanking.Rank(false, 3, 2, 1, 4);

        assertTrue(matched.compareTo(unmatched) < 0);
        assertTrue(morePatternNodes.compareTo(fewerPatternNodes) < 0);
        assertTrue(moreBindings.compareTo(fewerBindings) < 0);
        assertTrue(fewerResiduals.compareTo(moreResiduals) < 0);
        assertTrue(lowerResidualCost.compareTo(higherResidualCost) < 0);

        List<PreparationNearMatchRanking.Rank> repeated = new ArrayList<>(List.of(
            higherResidualCost,
            lowerResidualCost,
            higherResidualCost,
            lowerResidualCost));
        repeated.sort(null);
        assertEquals(List.of(
            lowerResidualCost,
            lowerResidualCost,
            higherResidualCost,
            higherResidualCost), repeated);
    }
}
