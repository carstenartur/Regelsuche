package de.regelsuche.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NoHardcodedDemoPathTest {
    @Test
    void macroImpactGeneratorDoesNotEmbedDemoExpressions() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/de/regelsuche/docs/MacroImpactReportGenerator.java"));

        assertFalse(source.contains("x ^ 2 + 6 * x + 5"));
        assertFalse(source.contains("(x + 3) ^ 2 - 4"));
        assertFalse(source.contains("complete-square factorization"));
        assertFalse(source.contains("x ^ 4 + 4 * y ^ 4"));
    }
}

class DiscoveryCorpusTest {
    @Test
    void containsPositiveNearMissAndNegativeFixturesForCoreDiscoveryCases() {
        for (String id : List.of(
                "sophie-germain",
                "complete-square",
                "difference-of-squares",
                "sum-of-cubes",
                "telescoping")) {
            String base = "discovery-corpus/" + id + "/";
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            assertNotNull(loader.getResource(base + "positive.txt"));
            assertNotNull(loader.getResource(base + "near-miss.txt"));
            assertNotNull(loader.getResource(base + "negative.txt"));
        }
    }
}
