package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.docs.HiddenRulePilotCampaign.PilotCase;
import de.regelsuche.docs.HiddenRulePilotEvaluator.HiddenReference;
import de.regelsuche.docs.HiddenRulePilotRunner.NegativeHoldout;
import de.regelsuche.docs.HiddenRulePilotRunner.PositiveHoldout;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HiddenRulePilotCampaignTest {
    @Test
    void emitsStableEvidenceWithoutPublishingHiddenIdentifiersOrWallClockTime() {
        RuntimeTask task = neutralElementTask();
        HiddenReference reference = new HiddenReference(
            "hidden_neutral_element_macro",
            "neutral-element-simplification",
            "(A + 0) * 1",
            "A",
            List.of(),
            List.of("neutral-element-simplification"));

        HiddenRulePilotCampaign.PilotReport report =
            new HiddenRulePilotCampaign().run(List.of(new PilotCase(task, reference)));
        String json = report.toJson();

        assertEquals(HiddenRulePilotCampaign.SCHEMA, report.schema());
        assertEquals(1, report.cases().size());
        assertEquals(1, report.familyCount());
        assertEquals(1, report.frozenCandidates());
        assertEquals(1, report.materialAblations());
        assertEquals(1, report.acceptedCases());
        assertEquals(json, report.toJson());
        assertTrue(json.contains("\"schema\":\"regelsuche.hidden-rule-pilot/v1\""));
        assertTrue(json.contains("\"splitPassed\":true"));
        assertTrue(json.contains("\"materialBenefit\":true"));
        assertFalse(json.contains("hidden_neutral_element_macro"));
        assertFalse(json.contains("elapsedNanos"));
    }

    private static RuntimeTask neutralElementTask() {
        Set<String> ids = Set.of("ast_add_zero_right", "ast_multiply_one_right");
        List<RewriteRule> primitives = AstRewriteTransformationEngine.defaultRules().stream()
            .filter(rule -> ids.contains(rule.id()))
            .toList();
        assertEquals(ids, primitives.stream().map(RewriteRule::id)
            .collect(java.util.stream.Collectors.toSet()));
        return new RuntimeTask(
            "case-report-001",
            "(x + 0) * 1",
            SearchTarget.valueEquivalent("x"),
            new AstRewriteTransformationEngine(primitives),
            new SearchHeuristic(4, 80, 1, 8, 40, 20),
            List.of(
                new PositiveHoldout("p-report-1", "((y + z) + 0) * 1", "y + z"),
                new PositiveHoldout("p-report-2", "(sin(t) + 0) * 1", "sin(t)")),
            List.of(
                new NegativeHoldout("n-report-1", "(y + 1) * 1"),
                new NegativeHoldout("n-report-2", "(y + 0) * 2")));
    }
}
