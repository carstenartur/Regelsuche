package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class SharedMultiPrincipalPreparationTraversalTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void reachesTwoPrincipalIdentitiesThroughOnePhysicalPreparationFrontier() {
        PatternRewriteRule preparation = rule(
            "shared_factor_difference_squares",
            expandedDifferenceOfSquares(),
            factoredDifferenceOfSquares(),
            RewriteKind.FACTOR);
        PatternRewriteRule firstPrincipal = rule(
            "shared_expand_difference_squares_first",
            factoredDifferenceOfSquares(),
            expandedDifferenceOfSquares(),
            RewriteKind.EXPAND);
        PatternRewriteRule secondPrincipal = rule(
            "shared_expand_difference_squares_second",
            factoredDifferenceOfSquares(),
            expandedDifferenceOfSquares(),
            RewriteKind.EXPAND);
        List<RewriteApplicabilitySchema> principals = List.of(
            RewriteApplicabilitySchema.fromPatternRule(firstPrincipal),
            RewriteApplicabilitySchema.fromPatternRule(secondPrincipal));
        var assumptions = AssumptionSignature.ofExpressions(List.of());
        var budget = budget();

        SharedMultiPrincipalPreparationTraversal shared =
            new SharedMultiPrincipalPreparationTraversal(
                principals,
                List.of(preparation),
                budget);
        var evaluation = shared.analyze("x^2-y^2", assumptions);

        var first = evaluation.outcome(firstPrincipal.id()).orElseThrow();
        var second = evaluation.outcome(secondPrincipal.id()).orElseThrow();
        assertEquals(
            SharedMultiPrincipalPreparationTraversal.Status.PREPARED_MATCH,
            first.status());
        assertEquals(
            SharedMultiPrincipalPreparationTraversal.Status.PREPARED_MATCH,
            second.status());
        assertEquals(1, first.preparationSteps().size());
        assertEquals(first.preparationSteps(), second.preparationSteps());
        assertEquals(1, evaluation.work().expandedStates());
        assertEquals(1, evaluation.work().generatedTransitions());
        assertEquals(2, evaluation.work().discoveredStates());
        assertEquals(1,
            evaluation.work().physicalWork().uniqueExpansions());
        assertEquals(1,
            evaluation.work().physicalWork().generatedTransformations());
        assertTrue(evaluation.work().physicalWork().analysisCacheHits() > 0);

        var legacyFirst = new PatternTargetedLocalBridgeSearch(
            firstPrincipal,
            List.of(preparation),
            REVISION,
            budget).analyze("x^2-y^2", assumptions);
        var legacySecond = new PatternTargetedLocalBridgeSearch(
            secondPrincipal,
            List.of(preparation),
            REVISION,
            budget).analyze("x^2-y^2", assumptions);
        assertEquals(PatternTargetedLocalBridgeSearch.Status.PREPARED,
            legacyFirst.status());
        assertEquals(PatternTargetedLocalBridgeSearch.Status.PREPARED,
            legacySecond.status());
        long legacyGenerated = (long) legacyFirst.work().generatedTransitions()
            + legacySecond.work().generatedTransitions();
        long legacyExpanded = (long) legacyFirst.work().expandedStates()
            + legacySecond.work().expandedStates();
        assertTrue(evaluation.work().generatedTransitions() < legacyGenerated);
        assertTrue(evaluation.work().expandedStates() < legacyExpanded);
    }

    @Test
    void sharesOneBudgetWithoutMergingPrincipalOutcomes() {
        PatternRewriteRule preparation = rule(
            "budget_factor_difference_squares",
            expandedDifferenceOfSquares(),
            factoredDifferenceOfSquares(),
            RewriteKind.FACTOR);
        PatternRewriteRule reachable = rule(
            "budget_reachable_principal",
            factoredDifferenceOfSquares(),
            expandedDifferenceOfSquares(),
            RewriteKind.EXPAND);
        PatternRewriteRule unrelated = rule(
            "budget_unrelated_principal",
            PatternExpr.op(
                BinaryOperator.ADD,
                PatternExpr.var("A"),
                PatternExpr.var("B")),
            PatternExpr.op(
                BinaryOperator.ADD,
                PatternExpr.var("B"),
                PatternExpr.var("A")),
            RewriteKind.NORMALIZE);
        var shared = new SharedMultiPrincipalPreparationTraversal(
            List.of(
                RewriteApplicabilitySchema.fromPatternRule(reachable),
                RewriteApplicabilitySchema.fromPatternRule(unrelated)),
            List.of(preparation),
            budget());

        var evaluation = shared.analyze(
            "x^2-y^2",
            AssumptionSignature.ofExpressions(List.of()));

        assertEquals(
            SharedMultiPrincipalPreparationTraversal.Status.PREPARED_MATCH,
            evaluation.outcome(reachable.id()).orElseThrow().status());
        assertEquals(
            SharedMultiPrincipalPreparationTraversal.Status
                .NO_MATCH_IN_COMPLETE_FROZEN_CLOSURE,
            evaluation.outcome(unrelated.id()).orElseThrow().status());
        assertEquals(2, evaluation.outcomes().size());
        assertEquals(2, evaluation.work().expandedStates(),
            "the unresolved principal exhausts the retained terminal state once");
        assertEquals(2,
            evaluation.work().physicalWork().uniqueExpansions());
    }

    @Test
    void multiPrincipalProductionOrderingFollowsVersionedAggregateRank() {
        PatternExpr principalPattern = PatternExpr.fn(
            "goal", PatternExpr.var("A"), PatternExpr.num(0));
        PatternRewriteRule principal = rule(
            "multi_rank_principal",
            principalPattern,
            PatternExpr.var("A"),
            RewriteKind.SIMPLIFY);
        PatternRewriteRule toward = rule(
            "multi_rank_toward",
            PatternExpr.fn("src", PatternExpr.var("A")),
            PatternExpr.fn("goal", PatternExpr.var("A"), PatternExpr.num(1)),
            RewriteKind.NORMALIZE);
        PatternRewriteRule worse = rule(
            "multi_rank_worse",
            PatternExpr.fn("src", PatternExpr.var("A")),
            PatternExpr.fn("other", PatternExpr.var("A")),
            RewriteKind.NORMALIZE);
        PatternRewriteRule finish = rule(
            "multi_rank_finish",
            PatternExpr.fn("goal", PatternExpr.var("A"), PatternExpr.num(1)),
            PatternExpr.fn("goal", PatternExpr.var("A"), PatternExpr.num(0)),
            RewriteKind.NORMALIZE);

        PatternMatchAnalyzer analyzer = new PatternMatchAnalyzer();
        ExpressionParser parser = new ExpressionParser();
        var towardAnalysis = analyzer.analyze(
            principalPattern,
            parser.parseTerm("goal(x,1)"),
            RecognitionProfile.exact());
        var worseAnalysis = analyzer.analyze(
            principalPattern,
            parser.parseTerm("other(x)"),
            RecognitionProfile.exact());
        assertTrue(PreparationNearMatchRanking.aggregate(List.of(towardAnalysis))
            .compareTo(PreparationNearMatchRanking.aggregate(List.of(worseAnalysis))) < 0);

        var traversal = new SharedMultiPrincipalPreparationTraversal(
            List.of(RewriteApplicabilitySchema.fromPatternRule(principal)),
            List.of(toward, worse, finish),
            rankingBudget());
        var evaluation = traversal.analyze(
            "src(x)",
            AssumptionSignature.ofExpressions(List.of()));
        var outcome = evaluation.outcome(principal.id()).orElseThrow();

        assertEquals(
            SharedMultiPrincipalPreparationTraversal.Status.PREPARED_MATCH,
            outcome.status());
        assertEquals(List.of("multi_rank_toward", "multi_rank_finish"),
            outcome.preparationSteps().stream()
                .map(PatternTargetedLocalBridgeSearch.Step::ruleId)
                .toList());
    }

    private static PatternRewriteRule rule(
        String id,
        PatternExpr source,
        PatternExpr target,
        RewriteKind kind
    ) {
        return new PatternRewriteRule(
            id,
            source,
            target,
            kind,
            false,
            -1,
            true);
    }

    private static PatternExpr expandedDifferenceOfSquares() {
        return PatternExpr.op(
            BinaryOperator.SUB,
            square("A"),
            square("B"));
    }

    private static PatternExpr factoredDifferenceOfSquares() {
        return PatternExpr.op(
            BinaryOperator.MUL,
            PatternExpr.op(
                BinaryOperator.SUB,
                PatternExpr.var("A"),
                PatternExpr.var("B")),
            PatternExpr.op(
                BinaryOperator.ADD,
                PatternExpr.var("A"),
                PatternExpr.var("B")));
    }

    private static PatternExpr square(String placeholder) {
        return PatternExpr.op(
            BinaryOperator.POW,
            PatternExpr.var(placeholder),
            PatternExpr.num(2));
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

    private static PatternTargetedLocalBridgeSearch.Budget rankingBudget() {
        return new PatternTargetedLocalBridgeSearch.Budget(
            2,
            8,
            16,
            4,
            64,
            1,
            32,
            500,
            250);
    }
}
