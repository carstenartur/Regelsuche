package de.regelsuche.docs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryCorpusTest {
    @Test
    void containsPositiveNearMissAndNegativeFixturesForCoreDiscoveryCases() {
        for (String id : java.util.List.of("sophie-germain", "complete-square", "difference-of-squares", "sum-of-cubes", "telescoping")) {
            String base = "discovery-corpus/" + id + "/";
            assertThat(Thread.currentThread().getContextClassLoader().getResource(base + "positive.txt")).isNotNull();
            assertThat(Thread.currentThread().getContextClassLoader().getResource(base + "near-miss.txt")).isNotNull();
            assertThat(Thread.currentThread().getContextClassLoader().getResource(base + "negative.txt")).isNotNull();
        }
    }
}
