package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.example.SeedExpression;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
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
        String markdown = Files.readString(bundle.markdownReport());
        assertTrue(markdown.contains("## Dashboard Metrics"));
        assertTrue(markdown.contains("## Semantic Discovery View"));
        assertTrue(markdown.contains("Renderer: replay-main-path"));
        assertTrue(markdown.contains("counterexampleStatus"));
        assertTrue(markdown.contains("replay step"));
        assertTrue(Files.readString(bundle.replayJson()).contains("regelsuche.discovery-replay/v1"));
        assertTrue(Files.readString(bundle.replayJson()).contains("\"dashboardMetrics\""));
        assertTrue(Files.readString(bundle.replayJson()).contains("\"counterexampleStatus\":\"COUNTEREXAMPLE_FOUND\""));
        assertTrue(Files.readString(bundle.hypothesesJson()).contains("regelsuche.hypotheses/v1"));
        assertTrue(Files.readString(bundle.hypothesesJson()).contains("\"counterexampleStatus\":\"COUNTEREXAMPLE_FOUND\""));
        assertTrue(Files.readString(bundle.macroRulesJson()).contains("regelsuche.macro-rules/v1"));
        assertTrue(Files.readString(bundle.counterexamplesJson()).contains("regelsuche.counterexamples/v1"));
        assertTrue(Files.readString(bundle.counterexamplesJson()).contains("\"counterexampleStatus\":\"COUNTEREXAMPLE_FOUND\""));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("regelsuche.provenance-graph/v1"));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"CounterexampleSearchAttempt\""));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"HYPOTHESIS_TESTED_BY\""));
        assertTrue(Files.readString(bundle.provenanceGraphJson()).contains("\"REFUTED_BY\""));
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
        BufferedImage screenshot = ImageIO.read(bundle.screenshotPng().toFile());
        assertEquals(new Color(22, 163, 74).getRGB(), screenshot.getRGB(220, 112),
            "PNG must render the semantic main-path node");
        BufferedImage replayFrame = ImageIO.read(bundle.replayGif().toFile());
        assertEquals(new Color(22, 163, 74).getRGB(), replayFrame.getRGB(220, 112),
            "GIF must render the semantic main-path node");
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
    void denseReplayReportSemanticViewCollapsesNoisyCanonicalVariants() throws Exception {
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report = denseReplayReport();

        DiscoveryReplayArtifactWriter.ArtifactBundle bundle = new DiscoveryReplayArtifactWriter()
            .write(report, tempDir, List.of(), denseSemanticView(report));
        String markdown = Files.readString(bundle.markdownReport());

        assertTrue(markdown.contains("- Main path nodes: 32"));
        assertTrue(markdown.contains("- Collapsed low-signal steps: 96"));
        assertEquals(24, countOccurrences(markdown, "semantic main step"));
        assertTrue(markdown.contains("0 + x"));
        assertTrue(markdown.contains("x^2+2*x+1*1"));
        assertTrue(Files.size(bundle.screenshotPng()) > 0);
        assertTrue(Files.size(bundle.replayGif()) > 0);
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

    @Test
    void provenanceCounterexampleEdgeUsesSingleGeneratedEdgeWithoutHypothesis() throws Exception {
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report = new DeterministicDiscoveryExperimentRunner.DiscoveryReport(
            List.of(new DeterministicDiscoveryExperimentRunner.SeedRunReport(
                new SeedExpression("no-hypothesis", "x", "generated", "none", List.of(), List.of()),
                false,
                "counterexample without hypothesis",
                List.of(),
                List.of("x=1"),
                de.regelsuche.validation.CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND,
                List.of("numeric-random"),
                List.of(),
                "refuting sample found",
                List.of("x"),
                1L,
                1L
            )),
            new DeterministicDiscoveryExperimentRunner.DiscoveryMetrics(1, 0, 0, 1, 1L, 1L),
            1L
        );

        DiscoveryReplayArtifactWriter.ArtifactBundle bundle = new DiscoveryReplayArtifactWriter().write(report, tempDir);
        String provenance = Files.readString(bundle.provenanceGraphJson());

        assertTrue(provenance.contains("\"type\":\"GENERATED\""));
        assertFalse(provenance.contains("\"type\":\"REFUTED_BY\""));
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

    private static DeterministicDiscoveryExperimentRunner.DiscoveryReport denseReplayReport() {
        List<DeterministicDiscoveryExperimentRunner.SeedRunReport> rows = new ArrayList<>();
        for (int seed = 0; seed < 8; seed++) {
            rows.add(new DeterministicDiscoveryExperimentRunner.SeedRunReport(
                new SeedExpression("dense-seed-" + seed, "x + 0", "dense", "polynomial", List.of("dense"), List.of()),
                true,
                "dense replay collapsed",
                List.of("hyp-dense-" + seed),
                List.of(),
                denseReplayPath(),
                20L + seed,
                2048L + seed
            ));
        }
        return new DeterministicDiscoveryExperimentRunner.DiscoveryReport(
            rows,
            new DeterministicDiscoveryExperimentRunner.DiscoveryMetrics(8, 8, 8, 0, 188L, 16412L),
            21L
        );
    }

    private static DiscoverySemanticReportView denseSemanticView(
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report
    ) {
        List<DiscoverySemanticReportView.SemanticPath> paths = report.rows().stream()
            .map(row -> {
                List<DiscoverySemanticReportView.SemanticNode> nodes = List.of(
                    new DiscoverySemanticReportView.SemanticNode(row.seed().id() + "-0", "x"),
                    new DiscoverySemanticReportView.SemanticNode(row.seed().id() + "-1", "x + 1"),
                    new DiscoverySemanticReportView.SemanticNode(row.seed().id() + "-2", "(x + 1)^2"),
                    new DiscoverySemanticReportView.SemanticNode(row.seed().id() + "-3", "x^2 + 2*x + 1")
                );
                List<DiscoverySemanticReportView.SemanticEdge> edges = List.of(
                    new DiscoverySemanticReportView.SemanticEdge(nodes.get(0).id(), nodes.get(1).id(),
                        "semantic main step", "MAIN_STEP", 0),
                    new DiscoverySemanticReportView.SemanticEdge(nodes.get(1).id(), nodes.get(2).id(),
                        "semantic main step", "MAIN_STEP", 0),
                    new DiscoverySemanticReportView.SemanticEdge(nodes.get(2).id(), nodes.get(3).id(),
                        "semantic main step", "MAIN_STEP", 0)
                );
                return new DiscoverySemanticReportView.SemanticPath(row.seed().id(), nodes, edges);
            })
            .toList();
        return new DiscoverySemanticReportView(
            "SemanticSearchGraphAssembler",
            128,
            120,
            32,
            24,
            96,
            96,
            paths
        );
    }

    private static List<String> denseReplayPath() {
        return List.of(
            "x + 0",
            "0 + x",
            "1*x",
            "x*1",
            "x",
            "x + 1",
            "x+1 + 0",
            "0 + x + 1",
            "(x + 1)^2",
            "(x+1)^2 + 0",
            "1*(x+1)^2",
            "(x+1)^2*1",
            "x^2 + 2*x + 1",
            "x^2+2*x+1 + 0",
            "1*x^2+2*x+1",
            "x^2+2*x+1*1"
        );
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
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
