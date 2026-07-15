package de.regelsuche.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutopilotArchitectureBoundariesTest {
    private static final Path REPO_ROOT = locateRepoRoot();

    @Test
    void compositionModuleOwnsTheLearningExperimentsIntegration() throws IOException {
        String build = Files.readString(
            REPO_ROOT.resolve("regelsuche-autopilot/build.gradle"));

        assertEquals(
            List.of(":regelsuche-experiments", ":regelsuche-learning"),
            projectDependencyTokens(build));
    }

    @Test
    void experimentsKernelDoesNotDependBackOnLearning() throws IOException {
        String build = Files.readString(
            REPO_ROOT.resolve("regelsuche-experiments/build.gradle"));

        assertFalse(build.contains("project(':regelsuche-learning')"));
        assertTrue(build.contains("project(':regelsuche-search')"));
        assertTrue(build.contains("project(':regelsuche-validation')"));
        assertTrue(build.contains("project(':regelsuche-discovery')"));
    }

    private static List<String> projectDependencyTokens(String buildFileContent) {
        return buildFileContent.lines()
            .filter(line -> line.contains("project(':"))
            .map(line -> line.substring(
                line.indexOf("project(':") + "project('".length()))
            .map(token -> token.substring(0, token.indexOf("')")))
            .toList();
    }

    private static Path locateRepoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("README.md"))
                && Files.exists(current.resolve("settings.gradle"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
            "Could not locate repository root from working directory");
    }
}
