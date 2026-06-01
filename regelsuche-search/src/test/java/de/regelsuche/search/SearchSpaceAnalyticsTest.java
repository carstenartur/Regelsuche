package de.regelsuche.search;

import de.regelsuche.knowledge.SearchEffect;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSpaceAnalyticsTest {
    @Test
    void summarizesGeneratedStatesAndRuleUsage() {
        SearchSpaceAnalytics analytics = SearchSpaceAnalytics.from(
                Map.of("x", 1L, "x+0", 2L, "x+0+0", 1L),
                Map.of("zero_identity", 3L, "macro_square", 1L),
                1);

        assertThat(analytics.generatedStates()).isEqualTo(4);
        assertThat(analytics.repeatedStates()).isEqualTo(1);
        assertThat(analytics.macroApplications()).isEqualTo(1);
        assertThat(analytics.topRules(1)).containsExactly(Map.entry("zero_identity", 3L));
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

        assertThat(analytics.topBridgeRules()).containsExactly(Map.entry("sophie_germain_bridge", 14L));
        assertThat(analytics.topFactorizationRules()).containsExactly(Map.entry("difference_of_squares", 3L));
        assertThat(analytics.topSimplificationRules()).containsExactly(Map.entry("sin_cos_identity", 5L));
        assertThat(analytics.topConvergentNodes()).containsExactly(Map.entry("bridge", 3L), Map.entry("target", 2L));
    }
}
