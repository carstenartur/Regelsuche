package de.regelsuche.search.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy.CandidateOutcome;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy.RankingEvent;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyCandidateBudgetSemanticsTest {
    private static final String BAD = "a-bad";
    private static final String GOOD = "z-good";

    @Test
    void guardRejectedTopRankDoesNotHideNextEnqueueableCandidate() {
        TransformationEngine engine = expression -> expression.equals("x")
            ? List.of(step(BAD, "x"), step(GOOD, "goal"))
            : List.of();
        SearchProblem problem = problem(engine, 1);

        var result = new PolicyAwareBestFirstSearchStrategy(policy(BAD, GOOD))
            .searchWithDiagnostics(problem);

        assertTrue(result.reached(), result.search().toString());
        assertEquals(List.of(GOOD), result.search().reachedState().appliedRuleIds());
        assertEquals(CandidateOutcome.SKIPPED_GUARD, event(result.policyEvents(), BAD).outcome());
        assertEquals(CandidateOutcome.ENQUEUED, event(result.policyEvents(), GOOD).outcome());
    }

    @Test
    void traceSeparatesRankFromTheRealBestFirstAdmissionOutcome() {
        TransformationEngine engine = expression -> expression.equals("x")
            ? List.of(step(GOOD, "goal"), step("z-later", "other"))
            : List.of();
        SearchProblem problem = problem(engine, 1);

        var result = new PolicyAwareBestFirstSearchStrategy(policy(GOOD, "z-later"))
            .searchWithDiagnostics(problem);

        RankingEvent admitted = event(result.policyEvents(), GOOD);
        RankingEvent budgeted = event(result.policyEvents(), "z-later");
        assertEquals(0, admitted.deterministicRank());
        assertEquals(CandidateOutcome.ENQUEUED, admitted.outcome());
        assertEquals(1, budgeted.deterministicRank());
        assertEquals(CandidateOutcome.NOT_CONSIDERED_BUDGET, budgeted.outcome());
        assertTrue(result.search().metrics().candidateBudgetPrunes() >= 1);
    }

    @Test
    void completeFallbackKeepsStaticSearchAndReportsItsActualBudgetPrefix() {
        TransformationEngine engine = expression -> expression.equals("x")
            ? List.of(step("a-other", "other"), step(GOOD, "goal"))
            : List.of();
        SearchProblem problem = problem(engine, 1);
        var staticResult = new BestFirstSearchStrategy().searchWithDiagnostics(problem);

        SearchPolicy fallback = new SearchPolicy() {
            @Override
            public String id() {
                return "fallback-test/v1";
            }

            @Override
            public PolicyDecision score(PolicyContext context, Transformation transformation) {
                return new PolicyDecision(
                    id(), context.targetDistance(), 0, true,
                    Map.of("targetDistance", context.targetDistance()),
                    "forced fallback for budget-trace verification");
            }
        };
        var policyResult = new PolicyAwareBestFirstSearchStrategy(fallback)
            .searchWithDiagnostics(problem);

        assertEquals(staticResult.status(), policyResult.search().status());
        assertEquals(staticResult.states(), policyResult.search().states());
        assertEquals(CandidateOutcome.ENQUEUED, event(policyResult.policyEvents(), GOOD).outcome());
        assertEquals(
            CandidateOutcome.NOT_CONSIDERED_BUDGET,
            event(policyResult.policyEvents(), "a-other").outcome());
    }

    private static SearchPolicy policy(String first, String second) {
        return new SearchPolicy() {
            @Override
            public String id() {
                return "test-order/v1";
            }

            @Override
            public PolicyDecision score(PolicyContext context, Transformation transformation) {
                int priority = transformation.rule().equals(first) ? 0 : 1;
                return new PolicyDecision(
                    id(), priority, 1000, false,
                    Map.of("testOrder", priority),
                    transformation.rule().equals(first)
                        ? "ranked first for the budget-semantics test"
                        : "ranked second for the budget-semantics test");
            }
        };
    }

    private static SearchProblem problem(TransformationEngine engine, int candidateBudget) {
        return new SearchProblem(
            "x",
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(2, 10, 1, 2, candidateBudget, 10))
            .withTarget(SearchTarget.syntaxExact("goal"));
    }

    private static RankingEvent event(List<RankingEvent> events, String rule) {
        return events.stream()
            .filter(candidate -> candidate.decisionGroup() == 0)
            .filter(candidate -> candidate.ruleId().equals(rule))
            .findFirst()
            .orElseThrow();
    }

    private static Transformation step(String rule, String output) {
        return new Transformation(
            rule,
            output,
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            rule + ":" + output);
    }
}
