package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.search.SearchSpaceAnalytics;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicBenchmarkEvidenceGateTest {
    private final PublicBenchmarkEvidenceGate gate = new PublicBenchmarkEvidenceGate();

    @Test
    void acceptsStructuredGeneratedBenchmarkEvidence() {
        PublicBenchmarkEvidenceGate.GateDecision decision = gate.evaluate(scenario(), evidence(true, true, false));

        assertTrue(decision.accepted(), decision.rejectionReasons().toString());
        assertEquals("DEGRADED", decision.ablationStatus());
        assertTrue(decision.structuredAblation());
    }

    @Test
    void rejectsMissingStructuredAblation() {
        PublicBenchmarkEvidenceGate.GateDecision decision = gate.evaluate(scenario(), evidence(true, false, false));

        assertFalse(decision.accepted());
        assertTrue(decision.rejectionReasons().contains("ablation=missing-structured"));
    }

    @Test
    void rejectsFallbackOrCuratedEdges() {
        PublicBenchmarkEvidenceGate.GateDecision decision = gate.evaluate(scenario(), evidence(true, true, true));

        assertFalse(decision.accepted());
        assertTrue(decision.rejectionReasons().contains("curated-or-fallback-path=true"));
    }

    @Test
    void writesGateJsonAndRejectionMarkdown(@TempDir Path tempDir) throws Exception {
        PublicBenchmarkEvidenceGate.GateDecision accepted = gate.evaluate(scenario(), evidence(true, true, false));
        PublicBenchmarkEvidenceGate.GateDecision rejected = gate.evaluate(scenario(), evidence(true, false, false));

        PublicBenchmarkEvidenceGate.GateReport report = gate.write(tempDir, List.of(accepted, rejected));

        assertEquals(1, report.acceptedCount());
        assertEquals(1, report.rejectedCount());
        assertTrue(Files.exists(tempDir.resolve("public-scenario-gate.json")));
        assertTrue(Files.exists(tempDir.resolve("public-scenario-rejections.md")));
        String markdown = Files.readString(tempDir.resolve("public-scenario-rejections.md"), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("ablation=missing-structured"));
    }

    private DiscoveryBenchmarkScenario scenario() {
        return new DiscoveryBenchmarkScenario(
            "scenario-a",
            "Scenario A",
            "x^2 + 6*x + 5",
            "(x + 1) * (x + 5)",
            List.of(),
            List.of("op"),
            List.of("pack"),
            List.of(),
            List.of(),
            List.of(),
            new DiscoveryBenchmarkScenario.MacroLearning(true, null, null),
            new DiscoveryBenchmarkScenario.Budgets(4, 50, 1000),
            new DiscoveryBenchmarkScenario.Gallery(true, 1, 2)
        );
    }

    private DiscoveryBenchmarkEvidence evidence(boolean success, boolean structuredAblation, boolean fallbackEdge) {
        DiscoveryBenchmarkEvidence.SearchRunEvidence without = structuredAblation
            ? new DiscoveryBenchmarkEvidence.SearchRunEvidence(
                true,
                "",
                List.of("input", "mid", "target"),
                List.of("rule-a", "rule-b"),
                new SearchSpaceAnalytics(30, 20, 0, 0, 0.0d))
            : null;
        DiscoveryBenchmarkEvidence.SearchRunEvidence with = structuredAblation
            ? new DiscoveryBenchmarkEvidence.SearchRunEvidence(
                true,
                "",
                List.of("input", "target"),
                List.of("macro-a"),
                new SearchSpaceAnalytics(5, 4, 0, 1, 0.0d))
            : null;
        String edgeSource = fallbackEdge ? "scenario-exact-path" : "operator";
        return new DiscoveryBenchmarkEvidence(
            "scenario-a",
            "x^2 + 6*x + 5",
            "(x + 1) * (x + 5)",
            success,
            success ? "" : "failed",
            without,
            with,
            success ? List.of(List.of("input", "target")) : List.of(),
            List.of("bridge-rule"),
            List.of("factorization"),
            List.of(),
            List.of("macro-a"),
            List.of("macro-a"),
            new SearchSpaceAnalytics(35, 20, 0, 1, 0.0d),
            success ? "PASS" : "FAIL",
            "AGREE",
            "oracle evidence",
            success,
            List.of(
                new DiscoveryBenchmarkEvidence.EvidenceNode("a", "input", "state"),
                new DiscoveryBenchmarkEvidence.EvidenceNode("b", "target", "state")),
            List.of(new DiscoveryBenchmarkEvidence.EvidenceEdge(
                "a", "b", "rule-a", "transform", edgeSource, "pack", "op", List.of(),
                "OPERATOR_DERIVED", true, List.of(), List.of())),
            ""
        );
    }
}
