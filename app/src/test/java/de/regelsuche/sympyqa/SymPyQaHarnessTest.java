package de.regelsuche.sympyqa;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymPyQaHarnessTest {
    @Test
    void writesQaReportsEvenWhenSymPyRuntimeIsUnavailable(@TempDir Path tempDir) throws Exception {
        SymPyQaHarness harness = new SymPyQaHarness();

        SymPyQaHarness.QaSummary summary = harness.runDefault(tempDir);

        assertTrue(summary.totalCases() > 0);
        assertTrue(Files.exists(tempDir.resolve("summary.json")));
        assertTrue(Files.exists(tempDir.resolve("disagreements.md")));
        assertTrue(Files.exists(tempDir.resolve("candidate-rules.md")));
        assertTrue(Files.exists(tempDir.resolve("interesting-discoveries.md")));
        assertTrue(Files.readString(tempDir.resolve("summary.json")).contains("\"schema\""));
    }
}
