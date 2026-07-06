package de.regelsuche.docs;

import de.regelsuche.search.SearchSpaceAnalytics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchSpacePowerReportWriterTest {

    private static final DiscoveryBenchmarkEvidence FIXED_EVIDENCE = buildFixedEvidence();

    // ── SearchSpacePowerReport.compute() ──────────────────────────────────────

    @Test
    void computeDerivesMetricsFromEvidence() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);

        assertEquals("power-test", report.scenarioId());
        assertEquals("a + b", report.inputExpression());
        assertEquals("target", report.targetExpression());
    }

    @Test
    void computeSelectedPathLengthEqualsAppliedRuleCount() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);

        assertEquals(2, report.selectedPathLength(),
                "selected path length must equal applied rule count in withoutMacroRun");
    }

    @Test
    void computeMaxDepthIsMaximumNodeDepth() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);

        assertEquals(3, report.maxExploredDepth());
    }

    @Test
    void computeDepthHistogramCountsNodesByDepth() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);

        assertEquals(2L, report.depthHistogram().get(0), "two nodes at depth 0");
        assertEquals(2L, report.depthHistogram().get(1), "two nodes at depth 1");
        assertEquals(1L, report.depthHistogram().get(2), "one node at depth 2");
        assertEquals(1L, report.depthHistogram().get(3), "one node at depth 3");
    }

    @Test
    void computeDeadEndCountMatchesTaggedNodes() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);

        assertEquals(1, report.deadEndCount(), "one node tagged dead-end");
    }

    @Test
    void computeSelectedPathNodesAndEdgesAreCorrect() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);

        assertEquals(3, report.selectedPathNodeCount(), "three selected-path nodes");
        assertEquals(3, report.selectedPathEdgeCount(), "three selected-path edges");
    }

    @Test
    void computeAlternativeBranchCountsAreCorrect() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);

        assertEquals(2, report.alternativeBranchNodeCount(), "two alternative-branch nodes");
        assertEquals(2, report.alternativeBranchEdgeCount(), "two alternative-branch edges");
    }

    @Test
    void computeEdgeSourceBreakdownReflectsEdgeSources() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);

        assertTrue(report.edgeSourceBreakdown().containsKey("core"), "must contain 'core' source");
        assertTrue(report.edgeSourceBreakdown().containsKey("operator"), "must contain 'operator' source");
    }

    @Test
    void computeEdgeKindBreakdownReflectsEdgeKinds() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);

        assertTrue(report.edgeKindBreakdown().containsKey("rule"), "must contain 'rule' kind");
        assertTrue(report.edgeKindBreakdown().containsKey("bridge"), "must contain 'bridge' kind");
    }

    @Test
    void computeTopRuleIdsContainUsedRules() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);

        assertFalse(report.topRuleIds().isEmpty(), "top rule ids must not be empty");
        assertTrue(report.topRuleIds().containsKey("rule.alpha"), "must include rule.alpha");
    }

    @Test
    void computePruningRatioIsBetweenZeroAndOne() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);

        assertTrue(report.pruningRatio() >= 0.0, "pruning ratio must be >= 0");
        assertTrue(report.pruningRatio() <= 1.0, "pruning ratio must be <= 1");
    }

    // ── Determinism ───────────────────────────────────────────────────────────

    @Test
    void reportIsStableAcrossMultipleCalls() {
        SearchSpacePowerReport first = SearchSpacePowerReport.compute(FIXED_EVIDENCE);
        SearchSpacePowerReport second = SearchSpacePowerReport.compute(FIXED_EVIDENCE);

        assertEquals(first, second, "report must be deterministic across calls");
    }

    // ── Writer: Markdown ──────────────────────────────────────────────────────

    @Test
    void markdownContainsScenarioIdAndInputTarget() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);
        String md = new SearchSpacePowerReportWriter().renderMarkdown(report);

        assertTrue(md.contains("power-test"), "markdown must contain scenario id");
        assertTrue(md.contains("a + b"), "markdown must contain input expression");
        assertTrue(md.contains("target"), "markdown must contain target expression");
    }

    @Test
    void markdownContainsDepthHistogramSection() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);
        String md = new SearchSpacePowerReportWriter().renderMarkdown(report);

        assertTrue(md.contains("Depth histogram"), "markdown must contain depth histogram section");
    }

    @Test
    void markdownContainsEdgeBreakdownSections() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);
        String md = new SearchSpacePowerReportWriter().renderMarkdown(report);

        assertTrue(md.contains("Edge/source breakdown"), "markdown must contain edge/source section");
        assertTrue(md.contains("Edge/kind breakdown"), "markdown must contain edge/kind section");
    }

    @Test
    void markdownContainsTopRuleSection() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);
        String md = new SearchSpacePowerReportWriter().renderMarkdown(report);

        assertTrue(md.contains("Top rule IDs"), "markdown must contain top rule IDs section");
    }

    @Test
    void markdownContainsSelectedPathVsAlternativeTable() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);
        String md = new SearchSpacePowerReportWriter().renderMarkdown(report);

        assertTrue(md.contains("Selected path vs alternatives"), "markdown must contain comparison section");
    }

    // ── Writer: SVG ───────────────────────────────────────────────────────────

    @Test
    void svgContainsProvenanceAttribute() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);
        String svg = new SearchSpacePowerReportWriter().renderSvg(report);

        assertTrue(svg.contains("data-generated-by=\"SearchSpacePowerReportWriter\""),
                "SVG must carry provenance attribute");
        assertTrue(svg.contains("data-scenario-id=\"power-test\""),
                "SVG must carry scenario-id attribute");
    }

    @Test
    void svgIsValidXmlFragment() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);
        String svg = new SearchSpacePowerReportWriter().renderSvg(report);

        assertTrue(svg.startsWith("<svg "), "SVG must start with <svg");
        assertTrue(svg.contains("</svg>"), "SVG must close with </svg>");
        assertFalse(svg.contains("${"), "SVG must have no unresolved placeholders");
    }

    @Test
    void svgIsDeterministic() {
        SearchSpacePowerReport report = SearchSpacePowerReport.compute(FIXED_EVIDENCE);
        String first = new SearchSpacePowerReportWriter().renderSvg(report);
        String second = new SearchSpacePowerReportWriter().renderSvg(report);

        assertEquals(first, second, "SVG output must be deterministic");
    }

    // ── Writer: file artifacts ─────────────────────────────────────────────────

    @Test
    void writeCreatesAllThreeArtifacts(@TempDir Path tempDir) throws Exception {
        new SearchSpacePowerReportWriter().write(tempDir, FIXED_EVIDENCE);

        assertTrue(Files.exists(tempDir.resolve("search-space-power.json")),
                "search-space-power.json must be written");
        assertTrue(Files.exists(tempDir.resolve("search-space-power.md")),
                "search-space-power.md must be written");
        assertTrue(Files.exists(tempDir.resolve("search-space-power.svg")),
                "search-space-power.svg must be written");
    }

    @Test
    void writtenJsonContainsScenarioId(@TempDir Path tempDir) throws Exception {
        new SearchSpacePowerReportWriter().write(tempDir, FIXED_EVIDENCE);
        String json = Files.readString(tempDir.resolve("search-space-power.json"), StandardCharsets.UTF_8);

        assertTrue(json.contains("power-test"), "JSON must contain scenarioId");
    }

    @Test
    void writtenSvgContainsScenarioId(@TempDir Path tempDir) throws Exception {
        new SearchSpacePowerReportWriter().write(tempDir, FIXED_EVIDENCE);
        String svg = Files.readString(tempDir.resolve("search-space-power.svg"), StandardCharsets.UTF_8);

        assertTrue(svg.contains("power-test"), "SVG must reference scenarioId");
    }

    @Test
    void writeIsIdempotent(@TempDir Path tempDir) throws Exception {
        SearchSpacePowerReportWriter writer = new SearchSpacePowerReportWriter();
        SearchSpacePowerReport first = writer.write(tempDir, FIXED_EVIDENCE);
        SearchSpacePowerReport second = writer.write(tempDir, FIXED_EVIDENCE);

        assertEquals(first, second, "repeated write must produce the same report");
    }

    // ── Fixed evidence builder ─────────────────────────────────────────────────

    private static DiscoveryBenchmarkEvidence buildFixedEvidence() {
        List<DiscoveryBenchmarkEvidence.EvidenceNode> nodes = List.of(
                new DiscoveryBenchmarkEvidence.EvidenceNode("n0", "a + b", "input", 0,
                        List.of("input", "selected-path")),
                new DiscoveryBenchmarkEvidence.EvidenceNode("n1", "step1", "state", 0,
                        List.of("alternative-branch")),
                new DiscoveryBenchmarkEvidence.EvidenceNode("n2", "step2", "state", 1,
                        List.of("selected-path")),
                new DiscoveryBenchmarkEvidence.EvidenceNode("n3", "dead-expr", "state", 1,
                        List.of("alternative-branch", "dead-end")),
                new DiscoveryBenchmarkEvidence.EvidenceNode("n4", "step3", "state", 2,
                        List.of("selected-path")),
                new DiscoveryBenchmarkEvidence.EvidenceNode("n5", "target", "target", 3,
                        List.of("target")));

        // Tag edges
        List<DiscoveryBenchmarkEvidence.EvidenceEdge> taggedEdges = List.of(
                new DiscoveryBenchmarkEvidence.EvidenceEdge(
                        "n0", "n2", "rule.alpha", "rule", "core", "core", "", List.of(), "", false,
                        List.of(), List.of("selected-path")),
                new DiscoveryBenchmarkEvidence.EvidenceEdge(
                        "n0", "n1", "rule.beta", "rule", "operator", "core", "", List.of(), "", false,
                        List.of(), List.of("alternative-branch")),
                new DiscoveryBenchmarkEvidence.EvidenceEdge(
                        "n1", "n3", "rule.gamma", "bridge", "operator", "core", "", List.of(), "", false,
                        List.of(), List.of("alternative-branch")),
                new DiscoveryBenchmarkEvidence.EvidenceEdge(
                        "n2", "n4", "rule.alpha", "rule", "core", "core", "", List.of(), "", false,
                        List.of(), List.of("selected-path")),
                new DiscoveryBenchmarkEvidence.EvidenceEdge(
                        "n4", "n5", "rule.delta", "bridge", "core", "core", "", List.of(), "", false,
                        List.of(), List.of("selected-path")));

        SearchSpaceAnalytics analytics = new SearchSpaceAnalytics(12, 8, 2, 0, 1.5);

        return new DiscoveryBenchmarkEvidence(
                "power-test",
                "a + b",
                "target",
                true,
                "",
                new DiscoveryBenchmarkEvidence.SearchRunEvidence(
                        true, "", List.of("n0", "n2", "n4", "n5"),
                        List.of("rule.alpha", "rule.alpha"), analytics),
                new DiscoveryBenchmarkEvidence.SearchRunEvidence(
                        false, "", List.of(), List.of(), analytics),
                List.of(List.of("a + b", "step2", "step3", "target")),
                List.of("rule.alpha"),
                List.of("core"),
                List.of(),
                List.of(),
                List.of(),
                analytics,
                "PASS",
                nodes,
                taggedEdges,
                "");
    }
}
