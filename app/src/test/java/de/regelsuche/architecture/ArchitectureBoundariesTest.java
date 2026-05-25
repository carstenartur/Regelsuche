package de.regelsuche.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ArchitectureBoundariesTest {

    private static final Path REPO_ROOT = locateRepoRoot();

    @Test
    void architectureDocumentsExist() {
        assertTrue(Files.exists(REPO_ROOT.resolve("docs/architecture.md")));
        assertTrue(Files.exists(REPO_ROOT.resolve("docs/module-structure.md")));
        assertTrue(Files.exists(REPO_ROOT.resolve("docs/dependency-rules.md")));
        assertTrue(Files.exists(REPO_ROOT.resolve("docs/testing-strategy.md")));
        assertTrue(Files.exists(REPO_ROOT.resolve("docs/adr/0001-logical-module-boundaries.md")));
    }

    @Test
    void mathematicalCoreHasNoInfrastructureImports() throws IOException {
        List<String> corePackages = List.of("ast", "parse", "canonical", "rules", "transform");
        List<String> forbiddenTokens = List.of(
            "org.neo4j",
            "org.springframework",
            "org.hibernate",
            "jakarta.persistence",
            "javax.persistence",
            "testcontainers",
            "docker"
        );
        Path mainJavaRoot = REPO_ROOT.resolve("app/src/main/java/de/regelsuche");

        for (String pkg : corePackages) {
            Path packagePath = mainJavaRoot.resolve(pkg);
            if (!Files.exists(packagePath)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(packagePath)) {
                files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> assertFileHasNoForbiddenToken(path, forbiddenTokens));
            }
        }
    }

    private static void assertFileHasNoForbiddenToken(Path file, List<String> forbiddenTokens) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException exception) {
            throw new RuntimeException("Could not read " + file, exception);
        }
        for (String forbidden : forbiddenTokens) {
            assertFalse(content.contains(forbidden),
                () -> "Core file must not reference infrastructure token '" + forbidden + "': " + file);
        }
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
        throw new IllegalStateException("Could not locate repository root from working directory");
    }
}
