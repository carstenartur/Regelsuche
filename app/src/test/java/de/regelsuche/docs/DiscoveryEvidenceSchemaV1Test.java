package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryEvidenceSchemaV1Test {
    private final DiscoveryEvidenceSchemaV1 schema = new DiscoveryEvidenceSchemaV1();

    @Test
    void validExamplesPassAndInvalidExamplesFailProfileChecks() throws IOException {
        Path examplesRoot = locateRepoRoot().resolve("docs/examples/discovery-evidence/v1");

        try (Stream<Path> paths = Files.list(examplesRoot)) {
            List<Path> validExamples = paths
                .filter(path -> path.getFileName().toString().startsWith("valid-"))
                .sorted()
                .toList();
            for (Path validExample : validExamples) {
                assertDoesNotThrow(() -> schema.assertValidDocument(schema.read(validExample), null), validExample.toString());
            }
        }

        try (Stream<Path> paths = Files.list(examplesRoot)) {
            List<Path> invalidExamples = paths
                .filter(path -> path.getFileName().toString().startsWith("invalid-"))
                .sorted()
                .toList();
            for (Path invalidExample : invalidExamples) {
                assertThrows(IllegalStateException.class,
                    () -> schema.assertValidDocument(schema.read(invalidExample), null),
                    invalidExample.toString());
            }
        }
    }

    @Test
    void committedGeneratedEvidenceValidatesAndArtifactTamperingChangesCanonicalHash(@TempDir Path tempDir) throws IOException {
        Path repoRoot = locateRepoRoot();
        Path generatedRoot = repoRoot.resolve("docs/generated/discovery");

        try (Stream<Path> paths = Files.walk(generatedRoot)) {
            for (Path evidenceFile : paths
                .filter(path -> path.getFileName().toString().equals("evidence.json"))
                .sorted()
                .toList()) {
                schema.assertValidDocument(schema.read(evidenceFile), evidenceFile.getParent());
            }
        }

        Path sourceScenarioDir = generatedRoot.resolve("complete-square");
        copyDirectory(sourceScenarioDir, tempDir);
        Path tempEvidence = tempDir.resolve("evidence.json");
        String recordedHash = schema.read(tempEvidence).path("canonicalEvidenceHash").asText();
        Files.writeString(
            tempDir.resolve("search-space.svg"),
            "\n<!-- tampered -->\n",
            StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.APPEND
        );

        String recomputed = schema.recomputeCanonicalEvidenceHash(schema.read(tempEvidence), tempDir);

        assertNotEquals(recordedHash, recomputed);
        assertThrows(IllegalStateException.class, () -> schema.assertValidDocument(schema.read(tempEvidence), tempDir));
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private static Path locateRepoRoot() {
        Path candidate = Paths.get(".").toAbsolutePath().normalize();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(candidate.resolve("README.md")) && Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
            candidate = candidate.getParent();
            if (candidate == null) {
                break;
            }
        }
        throw new IllegalStateException("Could not locate repository root");
    }
}
