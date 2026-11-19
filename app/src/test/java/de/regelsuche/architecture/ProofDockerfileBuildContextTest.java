package de.regelsuche.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Contracts for the Maven-built proof image. Runtime solver claims are
 * checked separately by the actual Testcontainers proof-image tests.
 */
class ProofDockerfileBuildContextTest {
    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    @Test
    void proofImageUsesTheCheckedMavenApplicationDistribution() throws IOException {
        String dockerfile = Files.readString(REPOSITORY_ROOT.resolve("Dockerfile.proof"));
        verifyBuilder(dockerfile);
        assertTrue(Files.readString(REPOSITORY_ROOT.resolve("pom.xml"))
            .contains("<module>app</module>"));
        assertTrue(dockerfile.contains("COPY --from=build /workspace/app-distribution/ ./"));
        assertTrue(dockerfile.contains("ENTRYPOINT [\"./bin/regelsuche\"]"));
        assertFalse(dockerfile.contains("./gradlew"));
        assertFalse(dockerfile.contains(":app:installDist"));
    }

    @Test
    void DockerContextKeepsOnlyThePublicMavenAssemblyInputs() throws IOException {
        String ignore = Files.readString(REPOSITORY_ROOT.resolve(".dockerignore"));
        for (String needed : new String[] {
            "!LICENSE", "!README.md", "!python/LICENSE",
            "!python/README.md", "!docs/python-client.md"
        }) {
            assertTrue(ignore.lines().anyMatch(needed::equals),
                () -> "Maven assembly input excluded by Docker context: " + needed);
        }
        assertTrue(ignore.contains("**/build"));
        assertTrue(ignore.contains("**/.gradle"));
        assertTrue(ignore.contains("**/out"));
        assertTrue(ignore.contains(".git"));
    }

    @Test
    void legacyOrUnpacklessBuildersCannotSatisfyTheContract() {
        String valid = """
            FROM maven:3.10.0-eclipse-temurin-25-noble AS build
            COPY . .
            RUN mvn --batch-mode --no-transfer-progress -Pfull -pl app -am \\
                    -Dmaven.test.skip=true package \\
                && tar -xf app/target/regelsuche-*.tar \\
                    -C /workspace/app-distribution --strip-components=1
            """;
        verifyBuilder(valid);
        assertThrows(AssertionError.class,
            () -> verifyBuilder(valid.replace("FROM maven:", "FROM gradle:")));
        assertThrows(AssertionError.class,
            () -> verifyBuilder(valid.replace("-Pfull -pl app -am", "-pl app")));
        assertThrows(AssertionError.class,
            () -> verifyBuilder(valid.replace("tar -xf", "echo")));
    }

    private static void verifyBuilder(String dockerfile) {
        assertTrue(dockerfile.contains("FROM maven:3.10.0-eclipse-temurin-25-noble AS build"),
            "The image must pin the declared JDK 25 Maven builder");
        assertTrue(dockerfile.contains("COPY . ."),
            "The build must receive the isolated source snapshot");
        assertTrue(dockerfile.contains(
            "mvn --batch-mode --no-transfer-progress -Pfull -pl app -am"),
            "The Maven app reactor is the distribution producer");
        assertTrue(dockerfile.contains("tar -xf app/target/regelsuche-*.tar"),
            "Use the Maven distribution, not Gradle installDist output");
        assertTrue(dockerfile.contains("--strip-components=1"),
            "The Maven distribution must be extracted into the runtime layout");
    }

    private static Path locateRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("Dockerfile.proof"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root");
    }
}
