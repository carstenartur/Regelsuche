package de.regelsuche.search.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.learning.SearchTrajectoryCollector;
import de.regelsuche.search.learning.SearchTrajectoryContext;
import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.learning.SearchTrajectoryDataset;
import de.regelsuche.search.learning.SearchTrajectoryRun;
import de.regelsuche.search.policy.DescriptorPolicyModel.Mode;
import de.regelsuche.search.policy.SearchPolicy.PolicyContext;
import de.regelsuche.search.policy.SearchPolicy.PolicyDecision;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy.PolicySearchResult;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy.RankingEvent;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DescriptorSearchPolicyTest {
    private static final String HELD_OUT_GOOD = "z-held-out-progress";
    private static final String HELD_OUT_BAD = "a-held-out-dead";

    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();

    @Test
    void unseenRuleIdsAreRankedFromDescriptorEvidenceUnderTheRealBudget() {
        DescriptorPolicyModel model = new DescriptorPolicyTrainer().train(
            new SearchTrajectoryDataset(List.of(trainingRun())), Mode.LINEAR, 1);
        SearchProblem problem = heldOutProblem();
        var staticResult = new BestFirstSearchStrategy().searchWithDiagnostics(problem);

        PolicySearchResult descriptorResult;
        try (DescriptorSearchPolicy policy = new DescriptorSearchPolicy(model, problem)) {
            descriptorResult = new PolicyAwareBestFirstSearchStrategy(policy)
                .searchWithDiagnostics(problem);
        }

        assertFalse(staticResult.reached(), staticResult.toString());
        assertTrue(descriptorResult.reached(), descriptorResult.search().toString());
        assertEquals(
            List.of(HELD_OUT_GOOD, "finish"),
            descriptorResult.search().reachedState().appliedRuleIds());
        RankingEvent good = firstGroupEvent(descriptorResult, HELD_OUT_GOOD);
        RankingEvent bad = firstGroupEvent(descriptorResult, HELD_OUT_BAD);
        assertFalse(good.fallback());
        assertFalse(bad.fallback());
        assertEquals(0, good.deterministicRank());
        assertTrue(good.explanation().contains("transparent descriptor score"));
        assertTrue(bad.contributions().keySet().stream()
            .anyMatch(feature -> feature.startsWith("feature.")));
        assertTrue(descriptorResult.policyEvents().stream()
            .noneMatch(event -> event.contributions().toString().contains(event.ruleId())));
    }

    @Test
    void changingOnlyTheRuleIdLeavesTheDescriptorDecisionUnchanged() {
        DescriptorPolicyModel model = new DescriptorPolicyTrainer().train(
            new SearchTrajectoryDataset(List.of(trainingRun())), Mode.LINEAR, 1);
        SearchProblem problem = problem(
            "q + 0",
            "q",
            expression -> List.of(),
            10);
        PolicyContext context = new PolicyContext("q + 0", 0, true, canonicalizer);
        Transformation first = step("first-rule-id", "q", -1);
        Transformation second = step("unrelated-rule-id", "q", -1);

        try (DescriptorSearchPolicy policy = new DescriptorSearchPolicy(model, problem)) {
            PolicyDecision firstDecision = policy.score(context, first);
            PolicyDecision secondDecision = policy.score(context, second);

            assertEquals(firstDecision, secondDecision);
            assertFalse(firstDecision.fallback());
        }
    }

    @Test
    void unavailableDescriptorFallsBackSafely() {
        DescriptorPolicyModel model = new DescriptorPolicyTrainer().train(
            new SearchTrajectoryDataset(List.of(trainingRun())), Mode.LINEAR, 1);
        SearchProblem problem = problem("x + 0", "x", expression -> List.of(), 10);
        PolicyContext context = new PolicyContext("x + 0", 100_000, true, canonicalizer);

        try (DescriptorSearchPolicy policy = new DescriptorSearchPolicy(model, problem)) {
            PolicyDecision decision = policy.score(
                context,
                step("unparseable-held-out-rule", "broken(", -1));

            assertTrue(decision.fallback());
            assertEquals(0, decision.confidencePermille());
            assertTrue(decision.explanation().contains("descriptor is unavailable"));
        }
    }

    @Test
    void descriptorDerivationFailureFallsBackInsteadOfAbortingSearch() {
        DescriptorPolicyModel model = new DescriptorPolicyTrainer().train(
            new SearchTrajectoryDataset(List.of(trainingRun())), Mode.LINEAR, 1);
        SearchProblem problem = problem("x + 0", "x", expression -> List.of(), 10);
        PolicyContext context = new PolicyContext("", 0, true, canonicalizer);

        try (DescriptorSearchPolicy policy = new DescriptorSearchPolicy(model, problem)) {
            PolicyDecision decision = policy.score(context, step("held-out", "x", -1));

            assertTrue(decision.fallback());
            assertTrue(decision.explanation().contains("descriptor derivation failed"));
        }
    }

    @Test
    void fallbackPreservesOrderingResolutionForLargeTargetDistances() {
        DescriptorPolicyModel model = new DescriptorPolicyTrainer().train(
            new SearchTrajectoryDataset(List.of(trainingRun())), Mode.LINEAR, 1);
        SearchProblem problem = problem("x + 0", "x", expression -> List.of(), 10);
        Transformation unparseable = step("unparseable", "broken(", -1);

        try (DescriptorSearchPolicy policy = new DescriptorSearchPolicy(model, problem)) {
            PolicyDecision nearer = policy.score(
                new PolicyContext("x + 0", 50_001, true, canonicalizer),
                unparseable);
            PolicyDecision farther = policy.score(
                new PolicyContext("x + 0", 100_000, true, canonicalizer),
                unparseable);

            assertTrue(nearer.fallback());
            assertTrue(farther.fallback());
            assertTrue(nearer.priority() < farther.priority());
            assertEquals(1_000_020, nearer.contributions().get("targetDistance"));
            assertEquals(2_000_000, farther.contributions().get("targetDistance"));
        }
    }

    private SearchTrajectoryRun trainingRun() {
        TransformationEngine engine = expression -> expression.equals("p + 0")
            ? List.of(
                step("z-train-good", "p", -1),
                step("a-train-bad", "p * 1", 1))
            : List.of();
        SearchTrajectoryCollector collector = new SearchTrajectoryCollector();
        SearchProblem problem = problem("p + 0", "p", engine, 10)
            .withObserver(collector);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertTrue(result.reached(), result.toString());
        return collector.finish(
            problem,
            result,
            new SearchTrajectoryContext(
                "descriptor-policy-train",
                "neutral-training-family",
                "descriptor-policy-ranking-test/v1",
                List.of("z-train-good", "a-train-bad"),
                DatasetSplit.TRAIN));
    }

    private SearchProblem heldOutProblem() {
        TransformationEngine engine = expression -> Map.of(
            "(x + 0) * 1", List.of(
                step(HELD_OUT_BAD, "x + 0", 1),
                step(HELD_OUT_GOOD, "x * 1", -1)),
            "x * 1", List.of(step("finish", "x", -1)))
            .getOrDefault(expression, List.of());
        return problem("(x + 0) * 1", "x", engine, 1);
    }

    private SearchProblem problem(
        String root,
        String target,
        TransformationEngine engine,
        int candidateBudget
    ) {
        return new SearchProblem(
            root,
            engine,
            scorer,
            canonicalizer,
            new SearchHeuristic(3, 20, 1, 2, candidateBudget, 10))
            .withTarget(SearchTarget.syntaxExact(target));
    }

    private static RankingEvent firstGroupEvent(PolicySearchResult result, String ruleId) {
        return result.policyEvents().stream()
            .filter(event -> event.decisionGroup() == 0)
            .filter(event -> event.ruleId().equals(ruleId))
            .findFirst()
            .orElseThrow();
    }

    private static Transformation step(String rule, String output, int costDelta) {
        return new Transformation(
            rule,
            output,
            RewriteKind.NORMALIZE,
            costDelta > 0,
            costDelta,
            true,
            rule + ":" + output);
    }
}
