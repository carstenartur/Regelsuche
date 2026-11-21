package de.regelsuche.quality.aggregate;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.quality.coverage.CoverageRegressionMain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MavenCoverageIT {
    @Test
    void allProductModuleReportsComeFromThisReactorAndMeetTheFrozenFloors() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        Instant start = Instant.parse(System.getProperty("regelsuche.reactorStart"));
        var policy = new ObjectMapper().readTree(root.resolve("config/quality/coverage-policy.json").toFile());
        for (var module : policy.required("modules").properties()) {
            Path report = root.resolve(module.getKey()).resolve("target/site/jacoco/jacoco.xml");
            assertTrue(Files.isRegularFile(report), report.toString());
            assertTrue(!Files.getLastModifiedTime(report).toInstant().isBefore(start),
                "Stale module coverage report from before the current reactor: " + report);
        }
        assertEquals(0, CoverageRegressionMain.run(new String[]{"--layout", "maven", "--root", root.toString()},
            System.out, System.err));
    }
}
