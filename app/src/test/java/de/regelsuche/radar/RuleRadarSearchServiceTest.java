package de.regelsuche.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.plugin.PluginRuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuleRadarSearchServiceTest {
    private AstRuleRadarService radar;
    private RuleRadarSearchService search;

    @BeforeEach
    void setUp() {
        radar = new AstRuleRadarService(
            new InMemoryRuleInventoryRepository(),
            new InMemoryExpressionGraphStore(),
            PluginRuntimeConfig.defaults());
        search = new RuleRadarSearchService(radar);
    }

    @AfterEach
    void tearDown() {
        radar.close();
    }

    @Test
    void searchEdgeUsesTheSameCandidateIdentityAsInspection() {
        AstRuleRadar.Snapshot snapshot = radar.inspect("(x + 1)^2 + 0", AstRuleRadar.Context.defaults());
        AstRuleRadar.ApplicableMove expected = snapshot.candidates().stream()
            .filter(candidate -> "root".equals(candidate.pathKey()))
            .filter(candidate -> "ast_add_zero_right".equals(candidate.ruleId()))
            .findFirst().orElseThrow();

        AstRuleRadar.SearchResult result = search.search(new RuleRadarSearchService.SearchRequest(
            "(x + 1)^2 + 0",
            "(x + 1)^2",
            AstRuleRadar.Context.defaults(),
            2,
            50,
            50));

        assertTrue(result.targetReached());
        assertEquals(1, result.edges().size());
        assertEquals(expected.candidateId(), result.edges().getFirst().candidateId());
        assertEquals(expected.pathKey(), result.edges().getFirst().pathKey());
        assertTrue(result.events().stream().anyMatch(event ->
            expected.candidateId().equals(event.candidateId())
                && event.outcome() == AstRuleRadar.CandidateOutcome.SELECTED));
        assertTrue(result.events().stream().anyMatch(event ->
            expected.candidateId().equals(event.candidateId())
                && event.outcome() == AstRuleRadar.CandidateOutcome.APPLIED));
    }

    @Test
    void globalStateBudgetIsReportedAsPruningNotMathematicalFailure() {
        AstRuleRadar.SearchResult result = search.search(new RuleRadarSearchService.SearchRequest(
            "(x + 1)^2 + 0",
            "",
            AstRuleRadar.Context.defaults(),
            3,
            1,
            50));

        assertFalse(result.targetReached());
        assertTrue(result.edges().isEmpty());
        assertTrue(result.events().stream().anyMatch(event ->
            event.outcome() == AstRuleRadar.CandidateOutcome.PRUNED_BUDGET
                && event.detail().contains("state budget")));
    }

    @Test
    void perStateMoveBudgetRetainsDistinctOutcome() {
        AstRuleRadar.SearchResult result = search.search(new RuleRadarSearchService.SearchRequest(
            "(x + 1)^2 + 0",
            "",
            AstRuleRadar.Context.defaults(),
            1,
            50,
            1));

        assertTrue(result.events().stream().anyMatch(event ->
            event.outcome() == AstRuleRadar.CandidateOutcome.PRUNED_BUDGET
                && event.detail().contains("per-state move budget")));
        assertTrue(result.finalOutcomeByCandidateId().containsValue(AstRuleRadar.CandidateOutcome.PRUNED_BUDGET));
    }
}
