package de.regelsuche.search;

import de.regelsuche.knowledge.SearchEffect;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BridgeAnalyticsTest {
    @Test
    void reportsOnlyRulesActuallyUsedAsBridges() {
        Map<String, Long> usage = new BridgeAnalyticsService().bridgeUsage(
                Map.of("Sophie-Germain", 7L, "Complete Square", 3L, "sin²+cos²", 9L),
                Map.of(
                        "Sophie-Germain", Set.of(SearchEffect.BRIDGING),
                        "Complete Square", Set.of(SearchEffect.BRIDGING),
                        "sin²+cos²", Set.of(SearchEffect.SIMPLIFYING)));

        assertEquals(Map.of("Sophie-Germain", 7L, "Complete Square", 3L), usage);
    }
}
