package de.regelsuche.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ArchitectureBoundariesTest {

    private static final Path REPO_ROOT = locateRepoRoot();

    @Test
    void teil0PortInterfacesExist() throws ClassNotFoundException {
        List<String> ports = List.of(
            "de.regelsuche.inventory.RuleIndex",
            "de.regelsuche.search.SearchTraceStore",
            "de.regelsuche.validation.CounterexampleSearchService",
            "de.regelsuche.equivalence.PolynomialEquivalenceService",
            "de.regelsuche.mining.HypothesisRepository",
            "de.regelsuche.benchmark.DiscoveryExperimentRunner"
        );
        for (String fqcn : ports) {
            Class<?> type = Class.forName(fqcn);
            assertTrue(type.isInterface(),
                () -> fqcn + " must be declared as an interface (Teil-0 \"Interfaces zuerst\")");
        }
    }

    @Test
    void physicalArchitectureModulesAreIncluded() throws IOException {
        String settings = Files.readString(REPO_ROOT.resolve("settings.gradle"));
        for (String module : List.of("regelsuche-core", "regelsuche-egraph",
            "regelsuche-search", "regelsuche-validation", "regelsuche-persistence",
            "regelsuche-experiments", "app")) {
            assertTrue(settings.contains("'" + module + "'"),
                () -> "settings.gradle must include :" + module);
            assertTrue(Files.exists(REPO_ROOT.resolve(module).resolve("build.gradle")),
                () -> "Expected Gradle build file for :" + module);
        }
    }

    @Test
    void physicalModuleDependenciesFollowTeil0Direction() throws IOException {
        Map<String, List<String>> expectedProjectDependencies = Map.of(
            "regelsuche-core", List.of(),
            "regelsuche-egraph", List.of(":regelsuche-core"),
            "regelsuche-search", List.of(":regelsuche-core", ":regelsuche-egraph"),
            "regelsuche-validation", List.of(":regelsuche-core"),
            "regelsuche-persistence", List.of(":regelsuche-core"),
            "regelsuche-experiments", List.of(":regelsuche-search", ":regelsuche-validation"),
            "app", List.of(":regelsuche-core", ":regelsuche-egraph", ":regelsuche-search",
                ":regelsuche-validation", ":regelsuche-persistence", ":regelsuche-experiments")
        );
        for (Map.Entry<String, List<String>> entry : expectedProjectDependencies.entrySet()) {
            String build = Files.readString(REPO_ROOT.resolve(entry.getKey()).resolve("build.gradle"));
            List<String> declared = projectDependencyTokens(build);
            for (String expected : entry.getValue()) {
                assertTrue(declared.contains(expected),
                    () -> entry.getKey() + " must depend on " + expected);
            }
            for (String declaredDependency : declared) {
                assertTrue(entry.getValue().contains(declaredDependency),
                    () -> entry.getKey() + " declares unexpected project dependency " + declaredDependency);
            }
        }
    }

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
        List<String> forbiddenTokens = List.of(
            "org.neo4j",
            "org.springframework",
            "org.hibernate",
            "jakarta.persistence",
            "javax.persistence",
            "testcontainers",
            "docker",
            "org.graalvm"
        );
        Path mainJavaRoot = REPO_ROOT.resolve("regelsuche-core/src/main/java");

        try (Stream<Path> files = Files.walk(mainJavaRoot)) {
            files.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> assertFileHasNoForbiddenToken(path, forbiddenTokens));
        }
        assertFileHasNoForbiddenToken(REPO_ROOT.resolve("regelsuche-core/build.gradle"), forbiddenTokens);
    }

    @Test
    void searchKernelHasNoPersistenceInfrastructureImports() throws IOException {
        List<String> forbiddenTokens = List.of(
            "org.neo4j",
            "org.hibernate",
            "jakarta.persistence",
            "javax.persistence",
            "testcontainers",
            "org.graalvm"
        );
        Path mainJavaRoot = REPO_ROOT.resolve("regelsuche-search/src/main/java");

        try (Stream<Path> files = Files.walk(mainJavaRoot)) {
            files.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> assertFileHasNoForbiddenToken(path, forbiddenTokens));
        }
        assertFileHasNoForbiddenToken(REPO_ROOT.resolve("regelsuche-search/build.gradle"), forbiddenTokens);
    }

    @Test
    void experimentsKernelHasNoPersistenceInfrastructureImports() throws IOException {
        List<String> forbiddenTokens = List.of(
            "org.neo4j",
            "org.hibernate",
            "jakarta.persistence",
            "javax.persistence",
            "testcontainers",
            "docker",
            "org.graalvm"
        );
        Path mainJavaRoot = REPO_ROOT.resolve("regelsuche-experiments/src/main/java");

        try (Stream<Path> files = Files.walk(mainJavaRoot)) {
            files.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> assertFileHasNoForbiddenToken(path, forbiddenTokens));
        }
        assertFileHasNoForbiddenToken(REPO_ROOT.resolve("regelsuche-experiments/build.gradle"), forbiddenTokens);
    }

    @Test
    void persistenceKernelHasNoDatabaseDriverImports() throws IOException {
        List<String> forbiddenTokens = List.of(
            "org.neo4j",
            "org.hibernate",
            "jakarta.persistence",
            "javax.persistence",
            "testcontainers",
            "org.graalvm"
        );
        Path mainJavaRoot = REPO_ROOT.resolve("regelsuche-persistence/src/main/java");

        try (Stream<Path> files = Files.walk(mainJavaRoot)) {
            files.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> assertFileHasNoForbiddenToken(path, forbiddenTokens));
        }
        assertFileHasNoForbiddenToken(REPO_ROOT.resolve("regelsuche-persistence/build.gradle"), forbiddenTokens);
    }

    private static List<String> projectDependencyTokens(String buildFileContent) {
        return buildFileContent.lines()
            .filter(line -> line.contains("project(':"))
            .map(line -> line.substring(line.indexOf("project(':") + "project('".length()))
            .map(token -> token.substring(0, token.indexOf("')")))
            .toList();
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
