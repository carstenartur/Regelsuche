package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.SumOfSquaresCompletionOperator;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class UntargetedEquivalentPathExtractorTest {
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final SumOfSquaresCompletionOperator completion =
        new SumOfSquaresCompletionOperator();
    private final TransformationEngine engine =
        completion::generateCandidates;
    private final SearchHeuristic heuristic =
        new SearchHeuristic(1, 16, 1, 4, 8, 16);

    @Test
    void retainsAnEquivalentRepresentationBridgeWithoutImmediateImprovement() {
        GoalSearchResult result = searchUntargeted();
        EquivalenceService exactForTest = (left, right) -> true;

        List<SuccessfulTransformationPath> paths =
            new UntargetedEquivalentPathExtractor(exactForTest)
                .extract(result);

        assertEquals(GoalStatus.UNTARGETED, result.status());
        assertEquals(1, paths.size(), paths.toString());
        SuccessfulTransformationPath path = paths.getFirst();
        assertEquals("x ^ 2 + y ^ 2", path.originalExpression());
        assertEquals(
            List.of(SumOfSquaresCompletionOperator.RULE_ID),
            path.rules());
        assertTrue(path.equivalenceVerified());
        assertTrue(path.equivalenceEvidence().startsWith(
            "untargeted-symbolic-equivalence:"));
        assertEquals("untargeted-search",
            path.variableStructure().get("source"));
        assertTrue(path.scoreImprovement() <= 0,
            "representation bridge unexpectedly improved immediately: "
                + path.scoreImprovement());
    }

    @Test
    void rejectsAnySearchResultThatHadAConcreteTarget() {
        String target = completion.generateCandidates("x^2 + y^2")
            .getFirst()
            .transformedExpression();
        SearchProblem problem = baseProblem()
            .withTarget(SearchTarget.syntaxExact(target));
        GoalSearchResult targeted = new BestFirstSearchStrategy()
            .searchWithDiagnostics(problem);

        assertTrue(targeted.reached(), targeted.toString());
        assertThrows(
            IllegalArgumentException.class,
            () -> new UntargetedEquivalentPathExtractor(
                (left, right) -> true).extract(targeted));
    }

    @Test
    void keepsTheConcreteEndpointOutsideTheUntargetedProblem() {
        SearchProblem problem = baseProblem().withoutTarget();
        GoalSearchResult result = new BestFirstSearchStrategy()
            .searchWithDiagnostics(problem);

        assertEquals(GoalStatus.UNTARGETED, result.status());
        assertFalse(result.reached());
        assertEquals(-1, result.bestDistance());
    }

    private GoalSearchResult searchUntargeted() {
        return new BestFirstSearchStrategy().searchWithDiagnostics(
            baseProblem().withoutTarget());
    }

    private SearchProblem baseProblem() {
        return new SearchProblem(
            "x^2 + y^2",
            engine,
            scorer,
            canonicalizer,
            heuristic)
            .withObjective(TransformationGoal.PROOF_FRIENDLY);
    }
}
