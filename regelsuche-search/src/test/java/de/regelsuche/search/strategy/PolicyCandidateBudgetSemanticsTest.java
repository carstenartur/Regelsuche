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
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyCandidateBudgetSemanticsTest {
    @Test
    void guardRejectedTopRankDoesNotHideTheNextEnqueueableCandidate() {
        TransformationEngine engine = expression -> switch (expression) {
            case "start" -> List.of(step("prepare", "middle", "repeat-key"));
            case "middle" -> List.of(
                step("blocked", "blocked-state", "repeat-key"),
                step("goal", "goal", "goal-key"));
            default -> List.of();
        };
        SearchPolicy policy = new SearchPolicy() {
            @Override
            public String id() {
                return "guard-first-test/v1";
            }

            @Override
            public PolicyDecision score(PolicyContext context, Transformation transformation) {
                int priority = transformation.rule().equals("blocked") ? 0 : 1;
                return new PolicyDecision(
                    id(), priority, 1000, false, Map.of("test", priority), "test ordering");
            }
        };
        SearchProblem problem = new SearchProblem(
            "start",
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(3, 20, 1, 2, 1, 5))
            .withTarget(SearchProblem.SearchTarget.syntaxExact("goal"));

        var result = new PolicyAwareBestFirstSearchStrategy(policy)
            .searchWithDiagnostics(problem);

        assertTrue(result.reached(), result.search().toString());
        assertEquals(List.of("prepare", "goal"),
            result.search().reachedState().appliedRuleIds());
        var blocked = result.policyEvents().stream()
            .filter(event -> event.parentExpression().equals("middle"))
            .filter(event -> event.ruleId().equals("blocked"))
            .findFirst().orElseThrow();
        var goal = result.policyEvents().stream()
            .filter(event -> event.parentExpression().equals("middle"))
            .filter(event -> event.ruleId().equals("goal"))
            .findFirst().orElseThrow();
        assertTrue(blocked.consideredBySearch());
        assertFalse(blocked.enqueued());
        assertEquals("repeated-rule-application", blocked.pruningReason());
        assertTrue(goal.consideredBySearch());
        assertTrue(goal.enqueued());
        assertTrue(goal.pruningReason().isBlank());
    }

    private static Transformation step(String rule, String output, String applicationKey) {
        return new Transformation(
            rule, output, RewriteKind.NORMALIZE, false, 0, true, applicationKey);
    }
}
