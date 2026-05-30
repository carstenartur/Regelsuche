package de.regelsuche.search.convergence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConvergentDiscoveryAnalysisTest {
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ConvergentDiscoveryAnalysis analysis = new ConvergentDiscoveryAnalysis();

    @Test
    void detectsTwoDifferentRulePathsToSameCanonicalResult() {
        ConvergentDiscoveryReport report = analysis.analyze("x^4 + 4*y^4", List.of(
            state(List.of("x^4 + 4*y^4", "(x^2 + 2*y^2)^2 - (2*x*y)^2", "(x^2 - 2*x*y + 2*y^2)*(x^2 + 2*x*y + 2*y^2)"),
                List.of("hypothesis_difference_of_squares_preparation", "ast_square_difference_factor")),
            state(List.of("x^4 + 4*y^4", "(x^2 - 2*x*y + 2*y^2)*(x^2 + 2*x*y + 2*y^2)"),
                List.of("macro_sophie_germain"))
        ), canonicalizer, scorer);

        assertTrue(report.isGalleryEligible());
        assertEquals(2, report.pathsToTarget().size());
        assertTrue(report.ruleFamiliesUsed().contains(RuleFamily.HIDDEN_STRUCTURE));
        assertTrue(report.ruleFamiliesUsed().contains(RuleFamily.LEARNED_MACRO));
        assertTrue(report.pathsToTarget().stream().allMatch(path -> path.sourceReplayIds().isEmpty()));
    }

    @Test
    void reportsCanonicalizedTargetExpression() {
        ConvergentDiscoveryReport report = analysis.analyze("x + 1", List.of(
            state(List.of("x + 1", "1 + x"), List.of("hypothesis_complete_square_preparation")),
            state(List.of("x + 1", "x + 1 + 0"), List.of("macro_complete_square"))
        ), canonicalizer, scorer);

        assertEquals(canonicalizer.canonicalize("1 + x"), report.canonicalTargetExpression());
    }

    @Test
    void rejectsDuplicatePathsWithSameRuleSequence() {
        ConvergentDiscoveryReport report = analysis.analyze("x", List.of(
            state(List.of("x", "x + 0", "x"), List.of("ast_add_zero_right", "ast_canonical_normalize")),
            state(List.of("x", "0 + x", "x"), List.of("ast_add_zero_right", "ast_canonical_normalize"))
        ), canonicalizer, scorer);

        assertFalse(report.isGalleryEligible());
        assertTrue(report.convergentStates().isEmpty());
    }

    @Test
    void identifiesSharedIntermediateStates() {
        ConvergentDiscoveryReport report = analysis.analyze("x", List.of(
            state(List.of("x", "x + 1", "x + 2"), List.of("hypothesis_difference_of_squares_preparation", "ast_square_difference_factor")),
            state(List.of("x", "x + 1", "x + 2"), List.of("macro_bridge", "macro_finish"))
        ), canonicalizer, scorer);

        assertEquals(1, report.sharedIntermediateStates().size());
        assertEquals("x + 1", report.sharedIntermediateStates().getFirst().expression());
    }

    @Test
    void classifiesPathFamilies() {
        RuleFamilyClassifier classifier = new RuleFamilyClassifier();

        assertEquals(RuleFamily.EXPANSION, classifier.classify("ast_distribute_left_add"));
        assertEquals(RuleFamily.COMPLETE_SQUARE, classifier.classify("hypothesis_complete_square_preparation"));
        assertEquals(RuleFamily.HIDDEN_STRUCTURE, classifier.classify("hypothesis_difference_of_squares_preparation"));
        assertEquals(RuleFamily.FACTORIZATION, classifier.classify("ast_square_difference_factor"));
        assertEquals(RuleFamily.LEARNED_MACRO, classifier.classify("macro_abcd"));
        assertEquals(RuleFamily.TELESCOPING, classifier.classify("hypothesis_telescoping_fraction"));
        assertEquals(RuleFamily.RATIONALIZATION, classifier.classify("hypothesis_rationalization"));
        assertEquals(RuleFamily.NORMALIZATION, classifier.classify("ast_canonical_normalize"));
    }

    private SearchState state(List<String> path, List<String> rules) {
        String expression = path.getLast();
        return new SearchState(
            expression,
            rules.size(),
            scorer.score(expression),
            path,
            rules,
            Set.copyOf(rules),
            0,
            canonicalizer.stableHash(expression),
            path.size() > 1 ? path.get(path.size() - 2) : null,
            rules.isEmpty() ? null : rules.getLast(),
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            1,
            rules.stream().map(ignored -> RewriteKind.NORMALIZE).toList(),
            rules.stream().map(ignored -> true).toList()
        );
    }
}
