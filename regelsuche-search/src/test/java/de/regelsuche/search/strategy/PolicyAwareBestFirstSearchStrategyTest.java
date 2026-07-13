package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
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
    void completeFallbackPreservesStaticSearchStatusAndExploredStates() {
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
            problem);

        assertEquals(staticResult.status(), fallbackResult.search().status());
        assertEquals(staticResult.states(), fallbackResult.search().states());
        assertEquals(staticResult.reachedState(), fallbackResult.search().reachedState());
        assertTrue(fallbackResult.policyEvents().stream().allMatch(RankingEvent::fallback));
    }

    private PolicySearchResult run(SearchPolicy policy, SearchProblem problem) {
        return new PolicyAwareBestFirstSearchStrategy(policy).searchWithDiagnostics(problem);
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
            .filter(event -> event.parentExpression().equals(parentExpression)
                && event.ruleId().equals(ruleId))
            .findFirst()
            .orElseThrow();
    }

    private static Transformation step(String rule, String output, String applicationKey) {
        return new Transformation(
            rule,
            output,
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            applicationKey);
    }
}
