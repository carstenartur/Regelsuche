package de.regelsuche.docs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DiscoveryCorpusTest {
    @Test
    void containsPositiveNearMissAndNegativeFixturesForCoreDiscoveryCases() {
        for (String id : java.util.List.of("sophie-germain", "complete-square", "difference-of-squares", "sum-of-cubes", "telescoping")) {
            String base = "discovery-corpus/" + id + "/";
            assertNotNull(Thread.currentThread().getContextClassLoader().getResource(base + "positive.txt"));
            assertNotNull(Thread.currentThread().getContextClassLoader().getResource(base + "near-miss.txt"));
            assertNotNull(Thread.currentThread().getContextClassLoader().getResource(base + "negative.txt"));
        }
    }
}
