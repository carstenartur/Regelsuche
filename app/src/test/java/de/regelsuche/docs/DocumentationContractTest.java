package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentationContractTest {
    @Test
    void macroImpactGeneratorDoesNotEmbedDemoExpressions() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/de/regelsuche/docs/MacroImpactReportGenerator.java"));

        assertFalse(source.contains("x ^ 2 + 6 * x + 5"));
        assertFalse(source.contains("(x + 3) ^ 2 - 4"));
        assertFalse(source.contains("complete-square factorization"));
        assertFalse(source.contains("x ^ 4 + 4 * y ^ 4"));
    }

    @Test
    void discoveryCorpusContainsAllCoreFixtureKinds() {
        for (String id : List.of(
                "sophie-germain",
                "complete-square",
                "difference-of-squares",
                "sum-of-cubes",
                "telescoping")) {
            String base = "discovery-corpus/" + id + "/";
            assertNotNull(resource(base + "positive.txt"));
            assertNotNull(resource(base + "near-miss.txt"));
            assertNotNull(resource(base + "negative.txt"));
        }
    }

    private static java.net.URL resource(String path) {
        return Thread.currentThread().getContextClassLoader().getResource(path);
    }
}
