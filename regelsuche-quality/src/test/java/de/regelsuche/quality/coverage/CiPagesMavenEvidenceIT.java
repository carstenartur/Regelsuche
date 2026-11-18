package de.regelsuche.quality.coverage;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Explicit read-only Maven reactor report replay. The existing Surefire and
 * Failsafe reports must come from an earlier successful build in this checkout;
 * this test neither executes source tests nor treats report counts as gate proof.
 */
@EnabledIfSystemProperty(named = "regelsuche.tests.mavenPages", matches = "true")
class CiPagesMavenEvidenceIT {
    @Test
    void renderRetainedMavenReports() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"))
            .toAbsolutePath().normalize();
        CiPagesMain.publishTests(root, root.resolve("public/maven-tests"),
            CiPagesMain.TestInput.MAVEN);
    }
}
