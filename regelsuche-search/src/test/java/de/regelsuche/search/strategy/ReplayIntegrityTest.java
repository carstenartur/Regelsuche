package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplayIntegrityTest {
    @Test
    void replayPathContainsOnlyActuallyGeneratedTransformations() {
        TransformationEngine engine = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(),
            List.of(new DifferenceOfSquaresPreparationOperator())
        );
        SearchProblem problem = new SearchProblem(
            "x^4 + 4",
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(2, 20, 1)
        );

        SearchState hypothesisState = new BestFirstSearchStrategy().search(problem).stream()
            .filter(state -> state.appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID))
            .findFirst()
            .orElseThrow();

        assertEquals(hypothesisState.depth() + 1, hypothesisState.path().size());
        assertEquals(hypothesisState.depth(), hypothesisState.appliedRuleIds().size());
        for (int index = 1; index < hypothesisState.path().size(); index++) {
            String previous = hypothesisState.path().get(index - 1);
            String current = hypothesisState.path().get(index);
            assertTrue(engine.transform(previous).stream().anyMatch(transformation ->
                transformation.transformedExpression().equals(current)
                    && transformation.rule().equals(hypothesisState.appliedRuleIds().get(index - 1))));
        }
    }
}
