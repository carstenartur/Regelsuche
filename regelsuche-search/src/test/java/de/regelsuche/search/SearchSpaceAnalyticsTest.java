package de.regelsuche.search;

import de.regelsuche.knowledge.SearchEffect;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchSpaceAnalyticsTest {
    @Test
    void summarizesGeneratedStatesAndRuleUsage() {
        SearchSpaceAnalytics analytics = SearchSpaceAnalytics.from(
                Map.of("x", 1L, "x+0", 2L, "x+0+0", 1L),
                Map.of("zero_identity", 3L, "macro_square", 1L),
                1);

        assertEquals(4, analytics.generatedStates());
        assertEquals(1, analytics.repeatedStates());
        assertEquals(1, analytics.macroApplications());
        assertEquals(Map.of("zero_identity", 3L), analytics.topRules(1));
    }

    @Test
    void reportsInfluentialRulesBySearchEffectAndConvergence() {
        SearchSpaceAnalytics analytics = SearchSpaceAnalytics.from(
                Map.of("input", 1L, "bridge", 3L, "target", 2L),
                Map.of("sophie_germain_bridge", 14L, "difference_of_squares", 3L, "sin_cos_identity", 5L),
                2,
                Map.of(
                        "sophie_germain_bridge", Set.of(SearchEffect.BRIDGING),
                        "difference_of_squares", Set.of(SearchEffect.FACTORIZING),
                        "sin_cos_identity", Set.of(SearchEffect.SIMPLIFYING)));

        assertEquals(Map.of("sophie_germain_bridge", 14L), analytics.topBridgeRules());
        assertEquals(Map.of("difference_of_squares", 3L), analytics.topFactorizationRules());
        assertEquals(Map.of("sin_cos_identity", 5L), analytics.topSimplificationRules());
        assertEquals(Map.of("bridge", 3L, "target", 2L), analytics.topConvergentNodes());
    }
}
