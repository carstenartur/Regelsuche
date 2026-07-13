package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.policy.DescriptorPolicyModel;
import de.regelsuche.search.policy.DescriptorPolicyModel.FeatureStatistics;
import de.regelsuche.search.policy.DescriptorPolicyModel.Mode;
import de.regelsuche.search.policy.DescriptorSearchPolicy;
import de.regelsuche.search.policy.SearchPolicy;
import de.regelsuche.search.policy.SearchPolicy.PolicyContext;
import de.regelsuche.search.policy.SearchPolicy.PolicyDecision;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy.PolicySearchResult;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy.RankingEvent;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyAwareBestFirstSearchStrategyTest {
    private static final String BAD_RULE = "a-bad";
    private static final String GOOD_RULE = "z-good";
    private static final String FINISH_RULE = "finish";

    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();

    @Test
    void guardRejectedCandidateDoesNotConsumeTheBestFirstBudget() {
        TransformationEngine engine = expression -> expression.equals("x")
            ? List.of(
                step("a-same", "x", "same"),
                step("b-progress", "y", "progress"))
            : List.of();
        SearchProblem problem = problem("x", SearchTarget.syntaxExact("y"), engine, 1);

        PolicySearchResult result = run(
            policy(Map.of("a-same", 0, "b-progress", 1), false),
            problem);

        assertTrue(result.reached(), result.search().toString());
        assertEquals(List.of("b-progress"), result.search().reachedState().appliedRuleIds());
        RankingEvent skipped = event(result, "x", "a-same");
        assertTrue(skipped.consideredBySearch());
        assertFalse(skipped.admittedToFrontier());
        assertEquals("skipped:same-expression", skipped.admissionOutcome());
        RankingEvent admitted = event(result, "x", "b-progress");
        assertTrue(admitted.consideredBySearch());
        assertTrue(admitted.admittedToFrontier());
        assertEquals("enqueued", admitted.admissionOutcome());
    }

    @Test
    void rankingTelemetrySeparatesRankFromTheRealCandidateBudgetOutcome() {
        TransformationEngine engine = expression -> expression.equals("x")
            ? List.of(
                step("a-target", "y", "target"),
                step("b-later", "z", "later"))
            : List.of();
        SearchProblem problem = problem("x", SearchTarget.syntaxExact("y"), engine, 1);

        PolicySearchResult result = run(
            policy(Map.of("a-target", 0, "b-later", 1), false),
            problem);

        RankingEvent target = event(result, "x", "a-target");
        RankingEvent later = event(result, "x", "b-later");
        assertEquals(0, target.deterministicRank());
        assertTrue(target.consideredBySearch());
        assertTrue(target.admittedToFrontier());
        assertEquals("enqueued", target.admissionOutcome());
        assertEquals(1, later.deterministicRank());
        assertFalse(later.consideredBySearch());
        assertFalse(later.admittedToFrontier());
        assertEquals("candidate-budget-not-considered", later.admissionOutcome());
        assertEquals(1, result.search().metrics().candidateBudgetPrunes());
    }

    @Test
    void duplicateCandidateDoesNotHideLaterEnqueueableCandidates() {
        TransformationEngine engine = expression -> switch (expression) {
            case "r" -> List.of(
                step("root-a", "x", "k1"),
                step("root-b", "z", "k2"));
            case "x" -> List.of(step("a-to-common", "x + y", "k2"));
            case "z" -> List.of(
                step("b-duplicate-common", "x + y", "k1"),
                step("b-unique-u", "u", "k3"),
                step("b-unique-v", "v", "k4"));
            default -> List.of();
        };
        SearchTarget target = SearchTarget.syntaxExact("x + y")
            .withDistanceWeight(1_000)
            .continueAfterReached();
        SearchProblem problem = problem("r", target, engine, 2);
        SearchPolicy policy = policy(Map.of(
            "root-a", 0,
            "root-b", 1,
            "a-to-common", 0,
            "b-duplicate-common", 0,
            "b-unique-u", 1,
            "b-unique-v", 2), false);

        PolicySearchResult result = run(policy, problem);

        assertTrue(result.reached(), result.search().toString());
        RankingEvent duplicate = event(result, "z", "b-duplicate-common");
        assertTrue(duplicate.consideredBySearch());
        assertFalse(duplicate.admittedToFrontier());
        assertEquals("duplicate-pruned", duplicate.admissionOutcome());
        for (String rule : List.of("b-unique-u", "b-unique-v")) {
            RankingEvent unique = event(result, "z", rule);
            assertTrue(unique.consideredBySearch(), rule);
            assertTrue(unique.admittedToFrontier(), rule);
            assertEquals("enqueued", unique.admissionOutcome(), rule);
        }
        assertTrue(result.search().metrics().duplicatePrunes() >= 1);
    }

    @Test
    void completeFallbackPreservesStaticSearchAndReportsActualAdmission() {
        TransformationEngine engine = expression -> expression.equals("x")
            ? List.of(
                step("a-dead-end", "z", "dead"),
                step("z-target", "y", "target"))
            : List.of();
        SearchProblem problem = problem("x", SearchTarget.syntaxExact("y"), engine, 1);
        BestFirstSearchStrategy.GoalSearchResult staticResult =
            new BestFirstSearchStrategy().searchWithDiagnostics(problem);

        PolicySearchResult fallbackResult = run(
            policy(Map.of("a-dead-end", 0, "z-target", 100), true),
            problem,
            PolicyAwareBestFirstSearchStrategy.DEFAULT_MAX_FRONTIER_ADJUSTMENT);

        assertEquals(staticResult.status(), fallbackResult.search().status());
        assertEquals(staticResult.states(), fallbackResult.search().states());
        assertEquals(staticResult.reachedState(), fallbackResult.search().reachedState());
        assertTrue(fallbackResult.policyEvents().stream().allMatch(RankingEvent::fallback));
        assertTrue(fallbackResult.policyEvents().stream()
            .allMatch(ranking -> ranking.frontierAdjustment() == 0));

        RankingEvent target = event(fallbackResult, "x", "z-target");
        RankingEvent deadEnd = event(fallbackResult, "x", "a-dead-end");
        assertTrue(target.consideredBySearch());
        assertTrue(target.admittedToFrontier());
        assertEquals("enqueued", target.admissionOutcome());
        assertFalse(deadEnd.consideredBySearch());
        assertFalse(deadEnd.admittedToFrontier());
        assertEquals("candidate-budget-not-considered", deadEnd.admissionOutcome());
    }

    @Test
    void descriptorEvidenceChangesDequeuePriorityAfterBothCandidatesAreAdmitted() {
        DescriptorSearchPolicy descriptorPolicy = descriptorPolicy();
        SearchProblem problem = contentionProblem();
        var staticResult = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        var candidateOnly = run(descriptorPolicy, problem);
        var frontier = run(descriptorPolicy, problem, 1_000);
        var replay = run(descriptorPolicy, problem, 1_000);

        assertFalse(staticResult.reached());
        assertFalse(candidateOnly.reached());
        assertTrue(frontier.reached(), frontier.search().toString());
        assertEquals(List.of(GOOD_RULE, FINISH_RULE),
            frontier.search().reachedState().appliedRuleIds());
        assertEquals(frontier, replay);

        RankingEvent good = event(frontier, "r", GOOD_RULE);
        RankingEvent bad = event(frontier, "r", BAD_RULE);
        assertTrue(good.admittedToFrontier() && bad.admittedToFrontier());
        assertTrue(good.frontierAdjustment() < 0);
        assertTrue(bad.frontierAdjustment() > 0);
        assertEquals(0, good.dequeueOrder());
        assertEquals(-1, bad.dequeueOrder());
        assertTrue(good.composedFrontierPriority() < bad.composedFrontierPriority());
    }

    @Test
    void frontierPriorityClampsExtremeEvidenceBeforeNarrowing() {
        SearchPolicy extreme = policy(
            Map.of("a", Integer.MAX_VALUE, "b", Integer.MAX_VALUE), false);
        TransformationEngine engine = expression -> expression.equals("r")
            ? List.of(step("a", "p", "a:p"), step("b", "s", "b:s"))
            : List.of();
        SearchProblem problem = new SearchProblem(
            "r", engine, scorer, canonicalizer,
            new SearchHeuristic(1, 4, 1, 2, 4, 8));

        RankingEvent event = event(run(extreme, problem, 1_000), "r", "a");

        assertEquals(1_000, event.frontierAdjustment());
        assertTrue(event.composedFrontierPriority() > 0);
        assertTrue(event.composedFrontierPriority() < Integer.MAX_VALUE);
    }

    private PolicySearchResult run(SearchPolicy policy, SearchProblem problem) {
        return run(policy, problem, 0);
    }

    private PolicySearchResult run(
        SearchPolicy policy,
        SearchProblem problem,
        int maxFrontierAdjustment
    ) {
        return new PolicyAwareBestFirstSearchStrategy(policy, maxFrontierAdjustment)
            .searchWithDiagnostics(problem);
    }

    private SearchProblem problem(
        String root,
        SearchTarget target,
        TransformationEngine engine,
        int candidateBudget
    ) {
        return new SearchProblem(
            root,
            engine,
            scorer,
            canonicalizer,
            new SearchHeuristic(4, 30, 1, 4, candidateBudget, 10))
            .withTarget(target);
    }

    private SearchProblem contentionProblem() {
        TransformationEngine engine = expression -> switch (expression) {
            case "r" -> List.of(
                descriptorStep(BAD_RULE, "p", 10, "finish:p"),
                descriptorStep(GOOD_RULE, "p", -10, "good:p"));
            case "p" -> List.of(descriptorStep(FINISH_RULE, "q", 0, "finish:p"));
            default -> List.of();
        };
        return new SearchProblem(
            "r", engine, scorer, canonicalizer,
            new SearchHeuristic(3, 3, 1, 2, 2, 10))
            .withTarget(SearchTarget.syntaxExact("q"));
    }

    private static DescriptorSearchPolicy descriptorPolicy() {
        DescriptorPolicyModel model = new DescriptorPolicyModel(
            "descriptor-frontier-test/v1",
            "sha256:train-only-source",
            "sha256:train-only-predictive",
            DescriptorPolicyModel.FEATURE_SCHEMA,
            Mode.LINEAR,
            1,
            Map.of(),
            Map.of("estimatedCostDelta",
                new FeatureStatistics(4, 2, 2, -10, 10, -10, 10, 1_000)));
        return new DescriptorSearchPolicy(model);
    }

    private static SearchPolicy policy(Map<String, Integer> priorities, boolean fallback) {
        return new SearchPolicy() {
            @Override
            public String id() {
                return "candidate-budget-test/v1";
            }

            @Override
            public PolicyDecision score(PolicyContext context, Transformation transformation) {
                int priority = priorities.getOrDefault(transformation.rule(), 1_000);
                return new PolicyDecision(
                    id(),
                    priority,
                    1_000,
                    fallback,
                    Map.of("testPriority", priority),
                    fallback ? "test fallback" : "test ranking");
            }
        };
    }

    private static RankingEvent event(
        PolicySearchResult result,
        String parentExpression,
        String ruleId
    ) {
        return result.policyEvents().stream()
            .filter(ranking -> ranking.parentExpression().equals(parentExpression)
                && ranking.ruleId().equals(ruleId))
            .findFirst()
            .orElseThrow();
    }

    private static Transformation step(String rule, String output, String applicationKey) {
        return descriptorStep(rule, output, 0, applicationKey);
    }

    private static Transformation descriptorStep(
        String rule,
        String output,
        int estimatedCostDelta,
        String applicationKey
    ) {
        return new Transformation(
            rule,
            output,
            RewriteKind.NORMALIZE,
            false,
            estimatedCostDelta,
            true,
            applicationKey);
    }
}