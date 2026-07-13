package de.regelsuche.search.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.policy.SearchPolicyModel.Mode;
import de.regelsuche.search.policy.SearchPolicyModel.RuleStatistics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.PolicyAwareBestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchPolicyFallbackTraceTest {
    @Test
    void completeFallbackMarksOnlyTheActualStaticBudgetPrefixAsSelected() {
        TransformationEngine engine = expression -> expression.equals("x")
            ? List.of(
                step("a-decoy", "z"),
                step("z-target", "y"))
            : List.of();
        SearchProblem problem = new SearchProblem(
            "x",
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(2, 10, 1, 2, 1, 4))
            .withTarget(SearchTarget.syntaxExact("y"));
        SearchPolicyModel incompatible = new SearchPolicyModel(
            "policy-v1:fallback-trace",
            "sha256:training-data",
            "regelsuche.search-policy-features/v0",
            "sha256:inventory",
            Mode.LINEAR,
            1,
            Map.of(
                "a-decoy", new RuleStatistics(1, 1, 0, 1000, 0),
                "z-target", new RuleStatistics(1, 1, 0, 1000, 0)));

        var staticResult = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        var policyResult = new PolicyAwareBestFirstSearchStrategy(
            new EmpiricalSearchPolicy(incompatible)).searchWithDiagnostics(problem);

        assertEquals(staticResult.status(), policyResult.search().status());
        assertEquals(staticResult.states(), policyResult.search().states());
        assertTrue(policyResult.reached());
        List<PolicyAwareBestFirstSearchStrategy.RankingEvent> rootEvents =
            policyResult.policyEvents().stream()
                .filter(event -> event.decisionGroup() == 0)
                .toList();
        assertEquals(2, rootEvents.size());
        assertTrue(rootEvents.stream().allMatch(
            PolicyAwareBestFirstSearchStrategy.RankingEvent::fallback));
        assertEquals(1, rootEvents.stream()
            .filter(PolicyAwareBestFirstSearchStrategy.RankingEvent::selectedByCandidateBudget)
            .count());
        assertEquals("z-target", rootEvents.stream()
            .filter(PolicyAwareBestFirstSearchStrategy.RankingEvent::selectedByCandidateBudget)
            .findFirst().orElseThrow().ruleId());
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
