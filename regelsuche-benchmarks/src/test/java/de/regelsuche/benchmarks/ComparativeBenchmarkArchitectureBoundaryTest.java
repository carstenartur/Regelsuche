package de.regelsuche.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ComparativeBenchmarkArchitectureBoundaryTest {
    private static final Pattern PROJECT_DEPENDENCY = Pattern.compile(
        "project\\(['\"](:[^'\"]+)['\"]\\)");

    @Test
    void benchmarkModuleDependsOnlyOnSearchAndSolverLayers()
            throws IOException {
        Path repository = repositoryRoot();
        String build = Files.readString(
            repository.resolve("regelsuche-benchmarks/build.gradle"));
        Set<String> actual = new TreeSet<>();
        Matcher matcher = PROJECT_DEPENDENCY.matcher(build);
        while (matcher.find()) {
            actual.add(matcher.group(1));
        }

        assertEquals(Set.of(
            ":regelsuche-egraph",
            ":regelsuche-search",
            ":regelsuche-solver-ir",
            ":regelsuche-solver-portfolio"), actual);
        assertFalse(build.contains("project(':app')"));
        assertFalse(build.contains("project(':regelsuche-release')"));
        assertFalse(build.contains("project(':regelsuche-autopilot')"));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
