package de.regelsuche.quality.coverage;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Explicit read-only Maven reactor report replay. The existing Surefire and
 * Failsafe reports must come from an earlier successful build of this checkout.
 * CI passes the root of its retained report snapshot.
 */
@EnabledIfSystemProperty(named = "regelsuche.tests.mavenPages", matches = "true")
class CiPagesMavenEvidenceIT {
    @Test
    void renderRetainedMavenReports() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"))
            .toAbsolutePath().normalize();
        Path reportRoot = Path.of(System.getProperty("regelsuche.tests.mavenReportRoot", root.toString()))
            .toAbsolutePath().normalize();
        CiPagesMain.publishTests(reportRoot, root.resolve("public/maven-tests"),
            CiPagesMain.TestInput.MAVEN);
    }
}
