package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.example.SeedExpression;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
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
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("regelsuche.reproducibility-pack/v1"));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"sha256\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"dependencies\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"discoveryState\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"gitCommit\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"enabledBackends\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"proofHistory\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"docker\""));
        assertTrue(Files.size(bundle.screenshotPng()) > 0, "PNG screenshot artifact must be written");
        assertTrue(Files.size(bundle.replayGif()) > 0, "GIF replay artifact must be written");
    }

    @Test
    void reproducibilityPackIsByteStableForSameInputs() throws Exception {
        System.setProperty("regelsuche.git.commit", "test-commit");
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report = sampleReport();
        var registry = new DefaultMathematicalAlgorithmRegistry(
            java.util.Map.of(
                MathematicalAlgorithmRegistry.PSLQ, true,
                MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH, true
            ),
            java.util.Map.of()
        );
        Path artifact = tempDir.resolve("artifact.txt");
        Files.writeString(artifact, "stable");
        DiscoveryReplayArtifactWriter writer = new DiscoveryReplayArtifactWriter();

        String first = writer.renderReproducibilityPack(report, List.of(artifact), registry.algorithms());
        String second = writer.renderReproducibilityPack(report, List.of(artifact), registry.algorithms());

        assertEquals(first, second);
        assertTrue(first.contains("\"seedSetHash\""));
        assertTrue(first.contains("\"toolchain\""));
        assertTrue(first.contains("\"algorithmRegistry\""));
        assertTrue(first.contains("\"artifacts\""));
        assertTrue(first.contains("\"gitCommit\":\"test-commit\""));
        assertTrue(first.contains("\"enabledBackends\":[\"numericRelationSearch\",\"pslq\"]"));
        System.clearProperty("regelsuche.git.commit");
    }

    private static DeterministicDiscoveryExperimentRunner.DiscoveryReport sampleReport() {
        return new DeterministicDiscoveryExperimentRunner.DiscoveryReport(
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
    }
}
