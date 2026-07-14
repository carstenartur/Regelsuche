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
import de.regelsuche.search.learning.TransformationDescriptor;
import de.regelsuche.search.policy.DescriptorPolicyModel.FeatureStatistics;
import de.regelsuche.search.policy.DescriptorPolicyModel.Mode;
import de.regelsuche.search.policy.SearchPolicy.PolicyContext;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PairwiseGoalEvidenceTest {
    private static final String FEATURE = "pairwise.targetReached";

    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();

    @Test
    void exactTargetSuccessorPrefersSpecificPairwiseEvidenceOverGlobalMarginals() {
        Transformation target = step("held-out-target", "x ^ 4");
        Transformation alternative = step("held-out-alternative", "(x ^ 2) ^ 2");
        FeatureStatistics targetEvidence = new FeatureStatistics(
            2, 1, 1, 1, 0, 0, 1, -1_000);
        FeatureStatistics harmfulGlobal = new FeatureStatistics(
            2, 1, 1, 0, 1, 0, 1, -1_000);
        DescriptorSearchPolicy policy = new DescriptorSearchPolicy(model(Map.of(
            FEATURE, targetEvidence,
            "estimatedCostDelta", harmfulGlobal)));

        var targetDecision = policy.score(
            new PolicyContext(
                "x ^ 2 * x ^ 2", 0, true, canonicalizer,
                descriptor("x ^ 2 * x ^ 2", target, "x ^ 4")),
            target);
        var alternativeDecision = new DescriptorSearchPolicy(model(Map.of(
            FEATURE, targetEvidence))).score(
                new PolicyContext(
                    "x ^ 2 * x ^ 2", 1, true, canonicalizer,
                    descriptor("x ^ 2 * x ^ 2", alternative, "x ^ 4")),
                alternative);

        assertFalse(targetDecision.fallback());
        assertEquals(0, targetDecision.contributions().get("targetDistance"));
        assertEquals(-1_000, targetDecision.contributions().get("descriptor." + FEATURE));
        assertFalse(targetDecision.contributions().containsKey("descriptor.estimatedCostDelta"));
        assertTrue(targetDecision.explanation().contains("targetCompetitionSupported=true"));
        assertTrue(alternativeDecision.fallback());
    }

    @Test
    void trainerLearnsTargetReachedOnlyFromRealCandidateCompetition() {
        SearchTrajectoryCollector collector = new SearchTrajectoryCollector();
        TransformationEngine engine = expression -> expression.equals("x ^ 2 * x ^ 2")
            ? List.of(
                step("train-target", "x ^ 4"),
                step("train-alternative", "(x ^ 2) ^ 2"))
            : List.of();
        SearchProblem problem = new SearchProblem(
            "x ^ 2 * x ^ 2",
            engine,
            scorer,
            canonicalizer,
            new SearchHeuristic(2, 20, 1, 2, 10, 10))
            .withTarget(SearchTarget.syntaxExact("x ^ 4"))
            .withObserver(collector);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertTrue(result.reached(), result.toString());
        var run = collector.finish(
            problem,
            result,
            new SearchTrajectoryContext(
                "train-pairwise-goal",
                "goal-family",
                "pairwise-goal-test/v1",
                List.of("train-target", "train-alternative"),
                DatasetSplit.TRAIN));

        DescriptorPolicyModel model = new DescriptorPolicyTrainer().train(
            new SearchTrajectoryDataset(List.of(run)), Mode.LINEAR, 1);
        FeatureStatistics statistics = model.features().get(FEATURE);

        assertEquals(2, statistics.observations());
        assertEquals(1, statistics.successfulChoices());
        assertEquals(1, statistics.failedAlternatives());
        assertEquals(-1_000, statistics.coefficientPermille());
    }

    private DescriptorPolicyModel model(Map<String, FeatureStatistics> features) {
        return new DescriptorPolicyModel(
            "descriptor-policy-v2:pairwise-goal",
            "sha256:train-source",
            "sha256:train-predictive",
            DescriptorPolicyModel.FEATURE_SCHEMA,
            Mode.LINEAR,
            1,
            Map.of(),
            features);
    }

    private TransformationDescriptor descriptor(
        String parentExpression,
        Transformation transformation,
        String targetExpression
    ) {
        try (TransformationDescriptor.Factory factory =
                new TransformationDescriptor.Factory(
                    SearchTarget.syntaxExact(targetExpression), canonicalizer)) {
            return factory.from(new SearchEvent(
                0,
                SearchEventType.TRANSFORMATION_GENERATED,
                transformation.transformedExpression(),
                "",
                1,
                0,
                "",
                parentExpression,
                transformation.rule(),
                transformation.kind(),
                transformation.mayIncreaseComplexity(),
                transformation.estimatedCostDelta(),
                transformation.equivalencePreservingByConstruction(),
                transformation.assumptions(),
                0,
                0,
                0,
                ""));
        }
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
