package de.regelsuche.demo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Tests that pin down the killer-demo documentation contract:
 * <ul>
 *   <li>the single Docker image is the primary/standard mode,</li>
 *   <li>Docker Compose is documented only as an <em>optional</em> Full Mode
 *       for persistent / Neo4j-backed analyses.</li>
 * </ul>
 *
 * <p>These tests guard against silent drift where someone could "promote"
 * Compose to the recommended way to run the demo and thereby break the
 * "5 minutes to wow" promise.</p>
 */
class DemoDocumentationTest {

    private static final Path REPO_ROOT = locateRepoRoot();

    @Test
    void singleDockerImageDocumentedAsPrimaryMode() throws IOException {
        String readme = readReadme();
        // Quickstart section must use the single-image flow and announce
        // it as the primary/standard demo mode.
        assertTrue(readme.contains("docker build -t regelsuche ."),
            "README must document `docker build -t regelsuche .`");
        assertTrue(readme.contains("docker run --rm -p 8080:8080 regelsuche"),
            "README must document `docker run --rm -p 8080:8080 regelsuche`");
        assertTrue(
            readme.contains("Killer-Demo Standard")
                || readme.contains("Standardmodus der Killer-Demo")
                || readme.contains("Standard"),
            "README must mark the single Docker image as the standard mode");
        // It must explicitly state "no external infrastructure required".
        assertTrue(readme.contains("ohne externe"),
            "README must state explicitly that the standard mode needs no external infrastructure");
    }

    @Test
    void dockerComposeDocumentedOnlyAsOptionalFullMode() throws IOException {
        String readme = readReadme();
        // Compose section exists ...
        assertTrue(readme.contains("docker compose up --build"),
            "README must mention `docker compose up --build`");
        // ... but only as Optional Full Mode.
        int composeIndex = readme.indexOf("docker compose up --build");
        // Heading immediately preceding the compose snippet must contain
        // both "Optional" and "Full Mode".
        String prelude = readme.substring(Math.max(0, composeIndex - 800), composeIndex);
        assertTrue(prelude.contains("Optional"),
            "Compose must be introduced under an 'Optional' heading");
        assertTrue(prelude.contains("Full Mode"),
            "Compose must be introduced as the Full Mode");
        // And the README must explicitly say that Compose is NOT a
        // prerequisite for the demo.
        assertTrue(readme.contains("nur optional") || readme.contains("nicht Voraussetzung")
                || readme.contains("nur optionaler"),
            "README must explicitly state Compose is only optional / not a prerequisite");

        // The actual compose file must exist (so the documentation has a
        // real artifact to point at).
        Path compose = REPO_ROOT.resolve("docker-compose.yml");
        assertTrue(Files.exists(compose), "docker-compose.yml must exist at the repo root");
        String composeContent = Files.readString(compose, StandardCharsets.UTF_8);
        assertTrue(composeContent.contains("neo4j"),
            "docker-compose.yml must wire a Neo4j service");
        assertTrue(composeContent.contains("NEO4J_URI"),
            "docker-compose.yml must export NEO4J_URI to the app");
        assertTrue(composeContent.contains("NEO4J_USER"),
            "docker-compose.yml must export NEO4J_USER to the app");
        assertTrue(composeContent.contains("NEO4J_PASSWORD"),
            "docker-compose.yml must export NEO4J_PASSWORD to the app");
        assertTrue(composeContent.contains("volumes:"),
            "docker-compose.yml must declare a persistent Neo4j volume");
    }

    private static String readReadme() throws IOException {
        Path readme = REPO_ROOT.resolve("README.md");
        assertNotNull(readme);
        assertTrue(Files.exists(readme), "README.md must exist at repository root");
        return Files.readString(readme, StandardCharsets.UTF_8);
    }

    private static Path locateRepoRoot() {
        // Tests run from the `app` subproject, so the repo root is the
        // parent of the working directory. Walk upwards until we find a
        // directory containing both README.md and settings.gradle.
        Path candidate = Paths.get(".").toAbsolutePath().normalize();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(candidate.resolve("README.md"))
                && Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
            Path parent = candidate.getParent();
            if (parent == null) {
                break;
            }
            candidate = parent;
        }
        throw new IllegalStateException(
            "Could not locate repository root from " + Paths.get(".").toAbsolutePath());
    }
}
