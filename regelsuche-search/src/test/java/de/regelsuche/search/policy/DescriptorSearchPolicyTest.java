package de.regelsuche.search.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.learning.TransformationDescriptor;
import de.regelsuche.search.policy.DescriptorPolicyModel.DescriptorStatistics;
import de.regelsuche.search.policy.DescriptorPolicyModel.FeatureStatistics;
import de.regelsuche.search.policy.DescriptorPolicyModel.Mode;
import de.regelsuche.search.policy.SearchPolicy.PolicyContext;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy;
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

class DescriptorSearchPolicyTest {
    private static final String ROOT = "(x + 0) * 1";
    private static final String BAD_RULE = "a-unseen-dead-end";
    private static final String GOOD_RULE = "z-unseen-progress";
    private static final String FINISH_RULE = "finish";

    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();

    @Test
    void linearDescriptorEvidenceRanksUnseenRuleIdsWithoutChangingApplicability() {
        SearchProblem problem = controlledProblem();
        var staticResult = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        DescriptorPolicyModel model = linearModel(DescriptorPolicyModel.FEATURE_SCHEMA);

        var learned = new PolicyAwareBestFirstSearchStrategy(new DescriptorSearchPolicy(model))
            .searchWithDiagnostics(problem);

        assertFalse(staticResult.reached(), staticResult.toString());
        assertTrue(learned.reached(), learned.search().toString());
        assertEquals(List.of(GOOD_RULE, FINISH_RULE),
            learned.search().reachedState().appliedRuleIds());
        assertEquals(GOOD_RULE, learned.policyEvents().stream()
            .filter(event -> event.decisionGroup() == 0)
            .findFirst().orElseThrow().ruleId());
        assertTrue(learned.policyEvents().stream()
            .filter(event -> event.decisionGroup() == 0)
            .noneMatch(PolicyAwareBestFirstSearchStrategy.RankingEvent::fallback));
        assertTrue(learned.policyEvents().stream()
            .filter(event -> event.decisionGroup() == 0)
            .flatMap(event -> event.contributions().keySet().stream())
            .anyMatch(name -> name.startsWith("descriptor.root.child.")));
        assertFalse(model.toPortableText().contains(BAD_RULE));
        assertFalse(model.toPortableText().contains(GOOD_RULE));
    }

    @Test
    void exactDescriptorFrequencyEvidenceIsIndependentOfConcreteRuleId() {
        Transformation transformation = step("held-out-rule-id", "x");
        TransformationDescriptor descriptor = descriptor("x + 0", transformation);
        DescriptorPolicyModel model = new DescriptorPolicyModel(
            "descriptor-policy-v1:test-frequency",
            "sha256:source",
            "sha256:predictive",
            DescriptorPolicyModel.FEATURE_SCHEMA,
            Mode.FREQUENCY,
            1,
            Map.of(descriptor.predictiveFingerprint(),
                new DescriptorStatistics(4, 3, 1, 750, -2)),
            Map.of());

        var decision = new DescriptorSearchPolicy(model).score(
            new PolicyContext("x + 0", 0, true, canonicalizer, descriptor),
            transformation);

        assertFalse(decision.fallback());
        assertTrue(decision.explanation().contains("rule-independent descriptor"));
        assertFalse(model.toPortableText().contains("held-out-rule-id"));
    }

    @Test
    void linearContributionClampsInLongSpaceBeforeNarrowing() {
        Transformation transformation = new Transformation(
            "extreme-cost",
            "x",
            RewriteKind.NORMALIZE,
            false,
            Integer.MAX_VALUE,
            true,
            "extreme-cost:x");
        TransformationDescriptor descriptor = descriptor("x + 0", transformation);
        DescriptorPolicyModel model = new DescriptorPolicyModel(
            "descriptor-policy-v1:extreme-range",
            "sha256:source",
            "sha256:predictive",
            DescriptorPolicyModel.FEATURE_SCHEMA,
            Mode.LINEAR,
            1,
            Map.of(),
            Map.of("estimatedCostDelta", new FeatureStatistics(
                2, 1, 1, 1, 0,
                Integer.MIN_VALUE, Integer.MAX_VALUE, 1000)));

        var decision = new DescriptorSearchPolicy(model).score(
            new PolicyContext("x + 0", 0, true, canonicalizer, descriptor),
            transformation);

        assertFalse(decision.fallback());
        assertEquals(1000, decision.contributions().get("descriptor.estimatedCostDelta"));
    }

    @Test
    void incompatibleDescriptorModelReproducesStaticSearchExactly() {
        SearchProblem problem = controlledProblem();
        var staticResult = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        DescriptorPolicyModel incompatible = linearModel("regelsuche.transformation-descriptor/v0");

        var fallback = new PolicyAwareBestFirstSearchStrategy(
            new DescriptorSearchPolicy(incompatible)).searchWithDiagnostics(problem);

        assertEquals(staticResult.status(), fallback.search().status());
        assertEquals(staticResult.states(), fallback.search().states());
        assertTrue(fallback.policyEvents().stream().allMatch(
            PolicyAwareBestFirstSearchStrategy.RankingEvent::fallback));
    }

    private SearchProblem controlledProblem() {
        TransformationEngine engine = expression -> Map.of(
            ROOT, List.of(
                step(BAD_RULE, "x + 0"),
                step(GOOD_RULE, "x * 1")),
            "x * 1", List.of(step(FINISH_RULE, "x")))
            .getOrDefault(expression, List.of());
        return new SearchProblem(
            ROOT,
            engine,
            scorer,
            canonicalizer,
            new SearchHeuristic(3, 10, 1, 2, 1, 10))
            .withTarget(SearchTarget.syntaxExact("x"));
    }

    private static DescriptorPolicyModel linearModel(String featureSchema) {
        Map<String, FeatureStatistics> features = Map.of(
            "root.child.ADD", new FeatureStatistics(4, 2, 2, 0, 1, 0, 1, 1000),
            "root.child.MUL", new FeatureStatistics(4, 2, 2, 1, 0, 0, 1, -1000));
        return new DescriptorPolicyModel(
            "descriptor-policy-v1:test-linear",
            "sha256:source",
            "sha256:predictive",
            featureSchema,
            Mode.LINEAR,
            1,
            Map.of(),
            features);
    }

    private TransformationDescriptor descriptor(
        String parentExpression,
        Transformation transformation
    ) {
        try (TransformationDescriptor.Factory factory =
                new TransformationDescriptor.Factory(
                    SearchTarget.syntaxExact(transformation.transformedExpression()),
                    canonicalizer)) {
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
