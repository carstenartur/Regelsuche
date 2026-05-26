package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.example.SeedExpression;
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
                List.of("x=0 breaks extrapolated template"),
                List.of("(x + 1)^2", "(x + 1) * (x + 1)", "x^2 + 2*x + 1"),
                12L,
                1024L
            )),
            new DeterministicDiscoveryExperimentRunner.DiscoveryMetrics(1, 1, 1, 1, 12L, 1024L),
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
        assertTrue(Files.readString(bundle.hypothesesJson()).contains("regelsuche.hypotheses/v1"));
        assertTrue(Files.readString(bundle.macroRulesJson()).contains("regelsuche.macro-rules/v1"));
        assertTrue(Files.readString(bundle.counterexamplesJson()).contains("regelsuche.counterexamples/v1"));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("regelsuche.provenance-graph/v1"));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"DERIVED_FROM\""));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"Seed\""));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"SearchRun\""));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"SupportingPath\""));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"Counterexample\""));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"CASAttempt\""));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"SymbolicRegressionProposal\""));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"SEEDED\""));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"SUPPORTED_BY\""));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"HAS_COUNTEREXAMPLE\""));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"CHECKED_BY\""));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"PROPOSES\""));
        assertTrue(Files.readString(bundle.campaignJson()).contains("regelsuche.discovery-campaign/v1"));
        assertTrue(Files.readString(bundle.campaignJson()).contains("\"kind\":\"descriptor\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("regelsuche.reproducibility-pack/v1"));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"sha256\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"dependencies\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"discoveryState\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"gitCommit\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"enabledBackends\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"hypotheses.json\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"provenance.graph.json\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"discovery-campaign.json\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"proofHistory\""));
        assertTrue(Files.readString(bundle.reproducibilityPack()).contains("\"docker\""));
        assertTrue(Files.size(bundle.screenshotPng()) > 0, "PNG screenshot artifact must be written");
        assertTrue(Files.size(bundle.replayGif()) > 0, "GIF replay artifact must be written");
    }

    @Test
    void discoveryCampaignRendersBudgetsBackendsAndArtifacts() {
        DiscoveryCampaign campaign = DiscoveryCampaign.fromReport(
            "campaign-polynomial-pack",
            sampleReport(),
            5,
            2,
            List.of("groebnerBasis", "pslq"),
            "JSON_FILE"
        );

        String json = campaign.renderJson();

        assertTrue(json.contains("regelsuche.discovery-campaign/v1"));
        assertTrue(json.contains("\"kind\":\"descriptor\""));
        assertTrue(json.contains("\"globalBudget\":5"));
        assertTrue(json.contains("\"parallelism\":2"));
        assertTrue(json.contains("\"groebnerBasis\""));
        assertTrue(json.contains("\"hypotheses.json\""));
        assertTrue(json.contains("\"provenance.graph.json\""));
    }

    @Test
    void reproducibilityPackIsByteStableForSameInputs() throws Exception {
        System.setProperty("regelsuche.git.commit", "test-commit");
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report = sampleReport();
        var algorithms = List.of(
            descriptor(MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH, true),
            descriptor(MathematicalAlgorithmRegistry.PSLQ, true)
        );
        Path artifact = tempDir.resolve("artifact.txt");
        Files.writeString(artifact, "stable");
        DiscoveryReplayArtifactWriter writer = new DiscoveryReplayArtifactWriter();

        String first = writer.renderReproducibilityPack(report, List.of(artifact), algorithms);
        String second = writer.renderReproducibilityPack(report, List.of(artifact), algorithms);

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
                List.of("x=0 breaks extrapolated template"),
                List.of("(x + 1)^2", "(x + 1) * (x + 1)", "x^2 + 2*x + 1"),
                12L,
                1024L
            )),
            new DeterministicDiscoveryExperimentRunner.DiscoveryMetrics(1, 1, 1, 1, 12L, 1024L),
            13L
        );
    }

    private static MathematicalAlgorithmRegistry.AlgorithmDescriptor descriptor(String id, boolean enabled) {
        return new MathematicalAlgorithmRegistry.AlgorithmDescriptor(
            id,
            id,
            enabled,
            MathematicalAlgorithmRegistry.AlgorithmBudget.bounded(1, 2, 3, 1e-8),
            MathematicalAlgorithmRegistry.ProofSemantics.HYPOTHESIS_ONLY,
            java.util.EnumSet.of(MathematicalAlgorithmRegistry.ResultType.HYPOTHESIS)
        );
    }
}
