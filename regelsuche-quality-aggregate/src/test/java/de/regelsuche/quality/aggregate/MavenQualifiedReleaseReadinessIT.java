package de.regelsuche.quality.aggregate;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.regelsuche.release.ReleaseReadinessEvidenceVerifier;
import de.regelsuche.release.ReleaseReadinessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Required verify-phase generation and verification after the actual Maven App producer. */
class MavenQualifiedReleaseReadinessIT {
    static final String INPUT = "app/target/reports/hidden-rule-pilot/report.json";
    static final String OUTPUT = "regelsuche-quality-aggregate/target/reports/release-readiness-qualified";

    @Test
    @Timeout(600)
    void currentMavenProductionPassesTheExistingQualifiedReleaseContract() throws Exception {
        String configured = System.getProperty("regelsuche.repositoryRoot");
        assertNotNull(configured, "the reactor must expose its repository root");
        generateAndVerify(Path.of(configured));
    }

    static void generateAndVerify(Path repository) throws IOException {
        Path root = repository.toAbsolutePath().normalize();
        Path input = root.resolve(INPUT);
        Path output = root.resolve(OUTPUT);
        requireNoSymbolicAncestors(input);
        requireNoSymbolicAncestors(output);
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) || Files.size(input) == 0) {
            throw new IOException("current Maven hidden-rule report is missing or not a nonempty regular file: " + input);
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("qualified Maven output already exists; initialize must remove prior evidence: " + output);
        }
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        ReleaseReadinessRunner runner = new ReleaseReadinessRunner();
        var run = runner.runQualified(input);
        runner.write(output, run);
        ReleaseReadinessEvidenceVerifier.verify(output, root.resolve("docs/schemas"));
        System.out.println("mavenReleaseReadinessInput=" + input);
        System.out.println("mavenReleaseReadinessRoot=" + output);
        System.out.println("mavenReleaseReadinessRun=" + run.contentHash());
        System.out.println("mavenReleaseReadinessStatus=VALID");
    }

    private static void requireNoSymbolicAncestors(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Maven release evidence path must not traverse a symbolic link: " + current);
            }
        }
    }
}
