package de.regelsuche.search.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.learning.ExpressionFingerprint;
import de.regelsuche.search.learning.InMemorySearchExperienceRepository;
import de.regelsuche.search.learning.SearchExperienceRepository.SearchExperience;
import de.regelsuche.search.learning.SearchTrajectoryCollector;
import de.regelsuche.search.learning.SearchTrajectoryContext;
import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.learning.SearchTrajectoryDataset;
import de.regelsuche.search.learning.SearchTrajectoryRun;
import de.regelsuche.search.policy.SearchPolicyModel.Mode;
import de.regelsuche.search.policy.SearchPolicyModel.RuleStatistics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExplainableSearchPolicyTest {
    private static final String BAD_RULE = "a-dead-end";
    private static final String GOOD_RULE = "z-progress";
    private static final String FINISH_RULE = "finish";
    private static final String EVALUATION_FAMILY = "two-step-evaluation";

    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();

    @Test
    void trainsLoadsAndReproducesByteStableTransparentModelMetadata() {
        SearchTrajectoryDataset firstDataset = trainingDataset();
        SearchTrajectoryDataset secondDataset = trainingDataset();
        SearchPolicyTrainer trainer = new SearchPolicyTrainer();

        SearchPolicyModel first = trainer.train(firstDataset, Mode.LINEAR, 1);
        SearchPolicyModel second = trainer.train(secondDataset, Mode.LINEAR, 1);
        SearchPolicyModel loaded = SearchPolicyModel.load(first.toPortableText());

        assertEquals(first, second);
        assertEquals(first, loaded);
        assertEquals(first.toJson(), second.toJson());
        assertEquals(first.toPortableText(), second.toPortableText());
        assertEquals(first.toPortableText(), loaded.toPortableText());
        assertTrue(first.modelVersion().startsWith("policy-v1:"));
        assertTrue(first.datasetHash().startsWith("sha256:"));
        assertEquals(SearchPolicyModel.FEATURE_SCHEMA, first.featureSchemaVersion());
        assertEquals(1000, first.rules().get(GOOD_RULE).successPermille());
        assertEquals(0, first.rules().get(BAD_RULE).successPermille());
        assertTrue(first.toJson().contains("\"datasetHash\""));
        assertTrue(first.toJson().contains("\"featureSchemaVersion\""));
        assertThrows(IllegalArgumentException.class,
            () -> SearchPolicyModel.load("regelsuche.search-policy-model/v0\ninvalid"));
    }

    @Test
    void heldOutRowsDoNotAffectModelIdentityOrWeights() {
        SearchTrajectoryDataset firstDataset = datasetWithHeldOut(
            "q * (r + s)", "q * r + q * s", "held-out-distribute");
        SearchTrajectoryDataset secondDataset = datasetWithHeldOut(
            "(r^2)^3", "r^6", "held-out-power");
        SearchPolicyTrainer trainer = new SearchPolicyTrainer();

        assertTrue(firstDataset.leakageFree(), firstDataset.leakageViolations().toString());
        assertTrue(secondDataset.leakageFree(), secondDataset.leakageViolations().toString());
        SearchPolicyModel first = trainer.train(firstDataset, Mode.LINEAR, 1);
        SearchPolicyModel second = trainer.train(secondDataset, Mode.LINEAR, 1);

        assertNotEquals(firstDataset.toJsonLines(), secondDataset.toJsonLines(),
            "the complete experimental datasets should actually differ");
        assertEquals(first, second,
            "model identity and weights must depend on TRAIN only");
    }

    @Test
    void learnedPolicySolvesControlledHeldOutTaskUnderTheSameBudget() {
        SearchPolicyModel model = new SearchPolicyTrainer().train(
            trainingDataset(), Mode.LINEAR, 1);
        SearchProblem problem = controlledHeldOutProblem();

        var staticResult = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        var learnedResult = runPolicy(model, null, problem);

        assertFalse(staticResult.reached(), staticResult.toString());
        assertTrue(staticResult.metrics().candidateBudgetPrunes() >= 1);
        assertTrue(learnedResult.reached(), learnedResult.search().toString());
        assertEquals(List.of(GOOD_RULE, FINISH_RULE),
            learnedResult.search().reachedState().appliedRuleIds());
        assertEquals(
            contribution(learnedResult, BAD_RULE, "targetDistance"),
            contribution(learnedResult, GOOD_RULE, "targetDistance"),
            "the learned rule evidence, not target distance, must break the first-step tie");
        assertTrue(learnedResult.policyEvents().stream()
            .anyMatch(event -> event.ruleId().equals(GOOD_RULE)
                && event.contributions().containsKey("empiricalFailure")
                && event.explanation().contains("transparent empirical score")));
        assertFirstRule(learnedResult, GOOD_RULE);
    }

    @Test
    void frequencyBaselineAlsoImprovesTheControlledHeldOutTask() {
        SearchPolicyModel model = new SearchPolicyTrainer().train(
            trainingDataset(), Mode.FREQUENCY, 1);

        var result = runPolicy(model, null, controlledHeldOutProblem());

        assertTrue(result.reached(), result.search().toString());
        assertFirstRule(result, GOOD_RULE);
        assertTrue(result.policyEvents().stream()
            .filter(event -> event.ruleId().equals(GOOD_RULE))
            .allMatch(event -> event.contributions().keySet()
                .equals(java.util.Set.of("targetDistance", "empiricalFailure"))));
    }

    @Test
    void experienceMemoryBreaksAnOtherwiseEqualLearnedTie() {
        SearchPolicyModel equalModel = new SearchPolicyModel(
            "policy-v1:equal-rules",
            "sha256:equal-dataset",
            SearchPolicyModel.FEATURE_SCHEMA,
            "sha256:equal-inventory",
            Mode.LINEAR_WITH_EXPERIENCE,
            1,
            Map.of(
                BAD_RULE, new RuleStatistics(2, 1, 1, 500, 0),
                GOOD_RULE, new RuleStatistics(2, 1, 1, 500, 0)));
        InMemorySearchExperienceRepository experiences = heldOutExperience();

        var result = runPolicy(equalModel, experiences, controlledHeldOutProblem());

        assertTrue(result.reached(), result.search().toString());
        assertFirstRule(result, GOOD_RULE);
        int goodExperience = contribution(result, GOOD_RULE, "experience");
        int badExperience = contribution(result, BAD_RULE, "experience");
        assertTrue(goodExperience < badExperience,
            "successful structural experience must outrank a failed alternative");
    }

    @Test
    void missingOrIncompatibleModelsFallBackToStaticSemantics() {
        SearchPolicyModel underObserved = new SearchPolicyTrainer().train(
            trainingDataset(), Mode.LINEAR, 2);
        SearchPolicyModel incompatible = new SearchPolicyModel(
            "policy-v1:incompatible",
            "sha256:dataset",
            "regelsuche.search-policy-features/v0",
            "sha256:inventory",
            Mode.LINEAR,
            1,
            underObserved.rules());
        SearchProblem problem = controlledHeldOutProblem();
        var staticResult = new BestFirstSearchStrategy().searchWithDiagnostics(problem);

        for (SearchPolicyModel model : List.of(underObserved, incompatible)) {
            var fallbackResult = runPolicy(model, null, problem);
            assertEquals(staticResult.status(), fallbackResult.search().status());
            assertEquals(staticResult.states(), fallbackResult.search().states());
            assertTrue(fallbackResult.policyEvents().stream().allMatch(event -> event.fallback()));
            assertTrue(fallbackResult.policyEvents().stream()
                .allMatch(event -> event.explanation().contains("fallback")));
        }
    }

    @Test
    void policyNeverMakesAnInapplicableRuleAvailable() {
        SearchPolicyModel model = new SearchPolicyTrainer().train(
            trainingDataset(), Mode.LINEAR, 1);
        TransformationEngine onlyBad = expression -> expression.equals("(x + 0) * 1")
            ? List.of(step(BAD_RULE, "x + 0"))
            : List.of();
        SearchProblem problem = problem(
            "(x + 0) * 1", "x", onlyBad,
            new SearchHeuristic(2, 10, 1, 2, 1, 10), null);

        var result = runPolicy(model, null, problem);

        assertFalse(result.reached());
        assertTrue(result.policyEvents().stream().noneMatch(event -> event.ruleId().equals(GOOD_RULE)));
    }

    private SearchTrajectoryDataset trainingDataset() {
        TransformationEngine engine = expression -> Map.of(
            "p + 0", List.of(
                step(GOOD_RULE, "p"),
                step(BAD_RULE, "p * 1")))
            .getOrDefault(expression, List.of());
        SearchTrajectoryCollector collector = new SearchTrajectoryCollector();
        SearchProblem problem = problem(
            "p + 0", "p", engine,
            new SearchHeuristic(2, 20, 1, 2, 10, 10), collector);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertTrue(result.reached());
        SearchTrajectoryRun run = collector.finish(
            problem,
            result,
            new SearchTrajectoryContext(
                "train-neutral",
                "neutral-training-family",
                "policy-test-producer/v1",
                List.of(BAD_RULE, GOOD_RULE),
                DatasetSplit.TRAIN));
        return new SearchTrajectoryDataset(List.of(run));
    }

    private SearchTrajectoryDataset datasetWithHeldOut(
        String input,
        String target,
        String ruleId
    ) {
        SearchTrajectoryRun trainingRun = trainingDataset().runs().getFirst();
        TransformationEngine engine = expression -> expression.equals(input)
            ? List.of(step(ruleId, target))
            : List.of();
        SearchTrajectoryCollector collector = new SearchTrajectoryCollector();
        SearchProblem problem = problem(
            input, target, engine,
            new SearchHeuristic(2, 20, 1, 2, 10, 10), collector);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertTrue(result.reached());
        SearchTrajectoryRun heldOutRun = collector.finish(
            problem,
            result,
            new SearchTrajectoryContext(
                "validation-" + ruleId,
                "validation-" + ruleId,
                "policy-test-producer/v1",
                List.of(ruleId),
                DatasetSplit.VALIDATION));
        return new SearchTrajectoryDataset(List.of(trainingRun, heldOutRun));
    }

    private InMemorySearchExperienceRepository heldOutExperience() {
        InMemorySearchExperienceRepository repository = new InMemorySearchExperienceRepository();
        ExpressionFingerprint parent = ExpressionFingerprint.of(
            "(x + 0) * 1", canonicalizer);
        ExpressionFingerprint target = ExpressionFingerprint.of("x", canonicalizer);
        ExpressionFingerprint goodChild = ExpressionFingerprint.of("x * 1", canonicalizer);
        ExpressionFingerprint badChild = ExpressionFingerprint.of("x + 0", canonicalizer);
        repository.store(new SearchExperience(
            "good-experience", "good-run", EVALUATION_FAMILY,
            parent.valueHash(), parent.alphaShapeHash(),
            goodChild.valueHash(), goodChild.alphaShapeHash(), target.alphaShapeHash(),
            GOOD_RULE, RewriteKind.NORMALIZE, List.of(),
            1, 0, 0, 0, true, true, GoalStatus.REACHED, ""));
        repository.store(new SearchExperience(
            "bad-experience", "bad-run", EVALUATION_FAMILY,
            parent.valueHash(), parent.alphaShapeHash(),
            badChild.valueHash(), badChild.alphaShapeHash(), target.alphaShapeHash(),
            BAD_RULE, RewriteKind.NORMALIZE, List.of(),
            1, 0, 0, 0, false, false, GoalStatus.FRONTIER_EXHAUSTED, "not-selected"));
        return repository;
    }

    private PolicyAwareBestFirstSearchStrategy.PolicySearchResult runPolicy(
        SearchPolicyModel model,
        InMemorySearchExperienceRepository experiences,
        SearchProblem problem
    ) {
        EmpiricalSearchPolicy policy = experiences == null
            ? new EmpiricalSearchPolicy(model)
            : new EmpiricalSearchPolicy(model, experiences, EVALUATION_FAMILY);
        return new PolicyAwareBestFirstSearchStrategy(policy).searchWithDiagnostics(problem);
    }

    private SearchProblem controlledHeldOutProblem() {
        TransformationEngine engine = expression -> Map.of(
            "(x + 0) * 1", List.of(
                step(BAD_RULE, "x + 0"),
                step(GOOD_RULE, "x * 1")),
            "x * 1", List.of(step(FINISH_RULE, "x")))
            .getOrDefault(expression, List.of());
        return problem(
            "(x + 0) * 1",
            "x",
            engine,
            new SearchHeuristic(3, 10, 1, 2, 1, 10),
            null);
    }

    private SearchProblem problem(
        String root,
        String target,
        TransformationEngine engine,
        SearchHeuristic heuristic,
        SearchTrajectoryCollector collector
    ) {
        SearchProblem problem = new SearchProblem(
            root, engine, scorer, canonicalizer, heuristic)
            .withTarget(SearchTarget.syntaxExact(target));
        return collector == null ? problem : problem.withObserver(collector);
    }

    private static void assertFirstRule(
        PolicyAwareBestFirstSearchStrategy.PolicySearchResult result,
        String expectedRule
    ) {
        assertEquals(expectedRule, result.policyEvents().stream()
            .filter(event -> event.decisionGroup() == 0)
            .findFirst().orElseThrow().ruleId());
    }

    private static int contribution(
        PolicyAwareBestFirstSearchStrategy.PolicySearchResult result,
        String rule,
        String feature
    ) {
        return result.policyEvents().stream()
            .filter(event -> event.decisionGroup() == 0 && event.ruleId().equals(rule))
            .findFirst().orElseThrow().contributions().getOrDefault(feature, 0);
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
