package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.example.SeedExpression;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryReplayArtifactWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesJsonHtmlMarkdownReplayScreenshotAndGifArtifacts() throws Exception {
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report = new DeterministicDiscoveryExperimentRunner.DiscoveryReport(
            List.of(new DeterministicDiscoveryExperimentRunner.SeedRunReport(
                new SeedExpression("identity-binomial-1", "(x + 1)^2", "known-identity", "binomial", List.of("scientific"), List.of()),
                true,
                "binomial reproduced",
                List.of("hyp-binomial"),
                List.of(),
                List.of("(x + 1)^2", "(x + 1) * (x + 1)", "x^2 + 2*x + 1"),
                12L,
                1024L
            )),
            new DeterministicDiscoveryExperimentRunner.DiscoveryMetrics(1, 1, 1, 0, 12L, 1024L),
            13L
        );

        DiscoveryReplayArtifactWriter.ArtifactBundle bundle = new DiscoveryReplayArtifactWriter().write(report, tempDir);

        assertTrue(Files.readString(bundle.jsonReport()).contains("regelsuche.discovery-report/v1"));
        assertTrue(Files.readString(bundle.htmlReport()).contains("Regelsuche Discovery Report"));
        assertTrue(Files.readString(bundle.htmlReport()).contains("searchSpaceSize"));
        assertTrue(Files.readString(bundle.htmlReport()).contains("artifactCounts"));
        assertTrue(Files.readString(bundle.htmlReport()).contains("Hypothesen"));
        assertTrue(Files.readString(bundle.markdownReport()).contains("## Dashboard Metrics"));
        assertTrue(Files.readString(bundle.replayJson()).contains("regelsuche.discovery-replay/v1"));
        assertTrue(Files.readString(bundle.replayJson()).contains("\"dashboardMetrics\""));
        assertTrue(Files.size(bundle.screenshotPng()) > 0, "PNG screenshot artifact must be written");
        assertTrue(Files.size(bundle.replayGif()) > 0, "GIF replay artifact must be written");
    }
}
