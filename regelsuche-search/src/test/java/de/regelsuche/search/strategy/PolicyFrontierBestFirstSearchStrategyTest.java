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
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy.RankingEvent;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyFrontierBestFirstSearchStrategyTest {
    private static final String BAD_RULE = "a-bad";
    private static final String GOOD_RULE = "z-good";
    private static final String FINISH_RULE = "finish";

    @Test
    void descriptorFrontierPriorityWinsWhenCandidateOrderingHasNoLeverage() {
        DescriptorSearchPolicy policy = descriptorPolicy();
        SearchProblem problem = contentionProblem();

        var staticResult = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        var candidateOnly = new PolicyAwareBestFirstSearchStrategy(policy)
            .searchWithDiagnostics(problem);
        var frontier = new PolicyAwareBestFirstSearchStrategy(
            policy, PolicyAwareBestFirstSearchStrategy.DEFAULT_MAX_FRONTIER_ADJUSTMENT)
            .searchWithDiagnostics(problem);

        assertFalse(staticResult.reached(), staticResult.toString());
        assertFalse(candidateOnly.reached(), candidateOnly.search().toString());
        assertTrue(frontier.reached(), frontier.search().toString());
        assertEquals(List.of(GOOD_RULE, FINISH_RULE),
            frontier.search().reachedState().appliedRuleIds());

        List<RankingEvent> rootEvents = frontier.policyEvents().stream()
            .filter(event -> event.parentExpression().equals("r"))
            .toList();
        assertEquals(2, rootEvents.size());
        assertTrue(rootEvents.stream().allMatch(RankingEvent::consideredBySearch));
        assertTrue(rootEvents.stream().allMatch(RankingEvent::admittedToFrontier));

        RankingEvent good = event(rootEvents, GOOD_RULE);
        RankingEvent bad = event(rootEvents, BAD_RULE);
        assertTrue(good.frontierAdjustment() < 0, good.toString());
        assertTrue(bad.frontierAdjustment() > 0, bad.toString());
        assertEquals(0, good.dequeueOrder());
        assertEquals(-1, bad.dequeueOrder());
        assertTrue(good.composedFrontierPriority() < bad.composedFrontierPriority());
    }

    @Test
    void completeFallbackIsExactlyStaticAndContributesZero() {
        SearchProblem problem = contentionProblem();
        var staticResult = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        DescriptorPolicyModel incompatible = new DescriptorPolicyModel(
            "descriptor-frontier-test/incompatible",
            "sha256:source",
            "sha256:predictive",
            "regelsuche.transformation-descriptor/v0",
            Mode.LINEAR,
            1,
            Map.of(),
            Map.of());

        var fallback = new PolicyAwareBestFirstSearchStrategy(
            new DescriptorSearchPolicy(incompatible),
            PolicyAwareBestFirstSearchStrategy.DEFAULT_MAX_FRONTIER_ADJUSTMENT)
            .searchWithDiagnostics(problem);

        assertEquals(staticResult.status(), fallback.search().status());
        assertEquals(staticResult.states(), fallback.search().states());
        assertEquals(staticResult.reachedState(), fallback.search().reachedState());
        assertTrue(fallback.policyEvents().stream().allMatch(RankingEvent::fallback));
        assertTrue(fallback.policyEvents().stream()
            .allMatch(event -> event.frontierAdjustment() == 0));
    }

    @Test
    void frontierEvidenceAndDequeueTraceAreByteDeterministic() {
        DescriptorSearchPolicy policy = descriptorPolicy();

        var first = new PolicyAwareBestFirstSearchStrategy(policy, 1000)
            .searchWithDiagnostics(contentionProblem());
        var second = new PolicyAwareBestFirstSearchStrategy(policy, 1000)
            .searchWithDiagnostics(contentionProblem());

        assertEquals(first.search(), second.search());
        assertEquals(first.policyEvents(), second.policyEvents());
    }

    @Test
    void extremePolicyContributionIsClampedBeforePriorityComposition() {
        SearchPolicy extreme = new SearchPolicy() {
            @Override
            public String id() {
                return "extreme-descriptor-test/v1";
            }

            @Override
            public PolicyDecision score(
                PolicyContext context,
                Transformation transformation
            ) {
                return new PolicyDecision(
                    id(), Integer.MAX_VALUE, 1000, false,
                    Map.of("descriptor.extreme", Integer.MAX_VALUE),
                    "deliberately extreme descriptor evidence");
            }
        };
        SearchProblem problem = new SearchProblem(
            "r",
            expression -> expression.equals("r")
                ? List.of(
                    step("unseen-a", "p", 0, "unseen-a:p"),
                    step("unseen-b", "s", 0, "unseen-b:s"))
                : List.of(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(1, 4, 1, 2, 4, 8));

        var result = new PolicyAwareBestFirstSearchStrategy(extreme, 1000)
            .searchWithDiagnostics(problem);
        RankingEvent event = event(result.policyEvents(), "unseen-a");

        assertEquals(1000, event.frontierAdjustment());
        assertTrue(event.composedFrontierPriority() < Integer.MAX_VALUE);
        assertTrue(event.composedFrontierPriority() > 0);
    }

    private static DescriptorSearchPolicy descriptorPolicy() {
        FeatureStatistics estimatedCost = new FeatureStatistics(
            4, 2, 2, -10, 10, -10, 10, 1000);
        DescriptorPolicyModel model = new DescriptorPolicyModel(
            "descriptor-frontier-test/v1",
            "sha256:train-only-source",
            "sha256:train-only-predictive",
            DescriptorPolicyModel.FEATURE_SCHEMA,
            Mode.LINEAR,
            1,
            Map.of(),
            Map.of("estimatedCostDelta", estimatedCost));
        return new DescriptorSearchPolicy(model);
    }

    private static SearchProblem contentionProblem() {
        TransformationEngine engine = expression -> switch (expression) {
            case "r" -> List.of(
                step(BAD_RULE, "p", 10, "finish:p"),
                step(GOOD_RULE, "p", -10, "good:p"));
            case "p" -> List.of(step(FINISH_RULE, "q", 0, "finish:p"));
            default -> List.of();
        };
        return new SearchProblem(
            "r",
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(3, 3, 1, 2, 2, 10))
            .withTarget(SearchProblem.SearchTarget.syntaxExact("q"));
    }

    private static RankingEvent event(List<RankingEvent> events, String ruleId) {
        return events.stream()
            .filter(event -> event.ruleId().equals(ruleId))
            .findFirst()
            .orElseThrow();
    }

    private static Transformation step(
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