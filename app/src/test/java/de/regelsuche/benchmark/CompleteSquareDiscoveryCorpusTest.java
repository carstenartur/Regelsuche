package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.math.algorithms.equivalence.PolynomialNormalFormEquivalenceService;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.CompleteSquareHypothesisOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.PolynomialBridgeAstPredicate;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompleteSquareDiscoveryCorpusTest {
    private final PolynomialNormalFormEquivalenceService polynomialEquivalence =
        new PolynomialNormalFormEquivalenceService(new DefaultMathematicalAlgorithmRegistry());

    @Test
    void completeSquareCorpusFindsValidatedBridgesAndRejectsNearMisses() {
        List<String> positives = List.of(
            "x^2 + 6*x + 5",
            "x^2 + 10*x + 21",
            "x^2 + 2*x*y + y^2",
            "y^2 + 8*y + 7",
            "(x + 1)^2 + 6*(x + 1) + 5"
        );
        List<String> nearMisses = List.of(
            "x^2 + 6*x + 6",
            "x^2 + 6*x + y",
            "x^2 + 2*x*y + z^2"
        );

        for (String expression : positives) {
            SearchState bridge = firstBridge(expression);
            assertTrue(bridge != null, expression);
            assertTrue(PolynomialBridgeAstPredicate.containsBridge(bridge.expression()), bridge.expression());
            for (String replayExpression : bridge.path()) {
                assertTrue(polynomialEquivalence.arePolynomiallyEquivalent(expression, replayExpression),
                    expression + " -> " + replayExpression);
            }
        }
        for (String expression : nearMisses) {
            assertFalse(new CompleteSquareHypothesisOperator().generateCandidates(expression).stream()
                .anyMatch(candidate -> polynomialEquivalence.arePolynomiallyEquivalent(expression, candidate.transformedExpression())),
                expression);
        }
    }

    private SearchState firstBridge(String expression) {
        TransformationEngine engine = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(AstRewriteTransformationEngine.defaultRules(), 128, 160),
            List.of(new CompleteSquareHypothesisOperator())
        );
        SearchProblem problem = new SearchProblem(
            expression,
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(3, 80, 1, 10, 200, 200)
        );
        return new BestFirstSearchStrategy().search(problem).stream()
            .filter(state -> state.appliedRuleIds().contains(CompleteSquareHypothesisOperator.RULE_ID))
            .filter(state -> PolynomialBridgeAstPredicate.containsBridge(state.expression()))
            .findFirst()
            .orElse(null);
    }
}
