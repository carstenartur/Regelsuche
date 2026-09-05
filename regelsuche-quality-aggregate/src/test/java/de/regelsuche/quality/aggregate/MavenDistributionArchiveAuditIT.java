package de.regelsuche.quality.aggregate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.quality.release.DistributionArchiveVerifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Runs only through the package-phase integrity execution, including -DskipTests package. */
class MavenDistributionArchiveAuditIT {
    @Test
    @Timeout(120)
    void actualProductArchivesMatchTheCurrentResolvedRuntimeGraph() throws Exception {
        String directory = System.getProperty("regelsuche.repositoryRoot");
        String version = System.getProperty("regelsuche.distributionVersion");
        assertNotNull(directory, "the reactor must expose its repository root");
        assertNotNull(version, "the package execution must expose the effective releaseVersion");
        Path root = Path.of(directory);
        Path evidence = root.resolve("regelsuche-quality-aggregate/target/discovery-artifacts/distribution-archive-audit.txt");
        // A failed incremental audit must not retain a previous successful receipt.
        Files.deleteIfExists(evidence);
        var report = DistributionArchiveVerifier.verify(root, version);
        assertTrue(report.runtimeLibraries() > 0);
        assertTrue(report.productModules() > 0);
        Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, report.summary() + "\n", StandardCharsets.UTF_8);
        System.out.println(report.summary());
    }
}
