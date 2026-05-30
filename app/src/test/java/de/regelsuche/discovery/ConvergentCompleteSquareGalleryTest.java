package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.convergence.ConvergentDiscoveryAnalysis;
import de.regelsuche.search.convergence.ConvergentDiscoveryReport;
import de.regelsuche.search.convergence.RuleFamily;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.ConservativeCompleteSquareHypothesisOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.QuadraticFactorizationHypothesisOperator;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConvergentCompleteSquareGalleryTest {
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    @Test
    void requiresCompleteSquareAndFactorizationPathsToSameResult() {
        String input = "x^2 + 6*x + 5";
        SearchProblem problem = new SearchProblem(
            input,
            new HypothesisTransformationEngine(
                new AstRewriteTransformationEngine(),
                List.of(
                    new ConservativeCompleteSquareHypothesisOperator(),
                    new QuadraticFactorizationHypothesisOperator()
                )
            ),
            scorer,
            canonicalizer,
            new SearchHeuristic(4, 240, 1, 24, 240, 240)
        );
        List<SearchState> states = new BestFirstSearchStrategy().search(problem);
        ConvergentDiscoveryReport report = new ConvergentDiscoveryAnalysis().analyze(problem, states);

        assertTrue(report.isGalleryEligible(),
            "Complete-square convergence needs two real paths; no static fallback is allowed. Report: " + report);
        assertTrue(report.ruleFamiliesUsed().contains(RuleFamily.COMPLETE_SQUARE), report.ruleFamiliesUsed().toString());
        assertTrue(report.ruleFamiliesUsed().contains(RuleFamily.FACTORIZATION), report.ruleFamiliesUsed().toString());
        assertTrue(report.pathsToTarget().stream().anyMatch(path ->
            path.ruleIds().contains(ConservativeCompleteSquareHypothesisOperator.RULE_ID)), report.pathsToTarget().toString());
        assertTrue(report.pathsToTarget().stream().anyMatch(path ->
            path.ruleIds().contains(QuadraticFactorizationHypothesisOperator.RULE_ID)), report.pathsToTarget().toString());
        assertTrue(new SymPyEquivalenceService().areEquivalent(input, report.canonicalTargetExpression()),
            report.canonicalTargetExpression());
    }
}
