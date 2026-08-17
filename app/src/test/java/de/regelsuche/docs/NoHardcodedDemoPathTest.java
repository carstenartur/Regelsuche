package de.regelsuche.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class NoHardcodedDemoPathTest {
    @Test
    void macroImpactGeneratorDoesNotEmbedDemoExpressions() throws Exception {
        String source = Files.readString(Path.of("src/main/java/de/regelsuche/docs/MacroImpactReportGenerator.java"));

        assertFalse(source.contains("x ^ 2 + 6 * x + 5"));
        assertFalse(source.contains("(x + 3) ^ 2 - 4"));
        assertFalse(source.contains("complete-square factorization"));
        assertFalse(source.contains("x ^ 4 + 4 * y ^ 4"));
    }
}
