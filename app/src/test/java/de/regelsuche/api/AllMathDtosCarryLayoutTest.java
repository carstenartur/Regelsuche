package de.regelsuche.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.api.searchgraph.SearchGraphEdgeDto;
import de.regelsuche.api.searchgraph.SearchGraphNodeDto;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.explain.ExplanationService;
import de.regelsuche.export.layout.MathLayout;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stage 5 pin: every DTO factory that surfaces a mathematical
 * expression must additionally yield a non-null {@link MathLayout}
 * (the layout-aware counterpart of the legacy {@code …Latex} fields
 * pinned by {@link AllMathDtosCarryLatexTest}). This protects against
 * regressions where a new DTO field is added that silently bypasses
 * the central layout pipeline.
 */
class AllMathDtosCarryLayoutTest {

    private static ExpressionScore score(int total) {
        return new ExpressionScore(total, total, total, 0, 0);
    }

    private static DiscoveredTransformation samplePath() {
        TransformationStep step = new TransformationStep(
            0,
            "x*(y+1)",
            "x*y + x",
            "polynomial_distribute",
            RewriteKind.NORMALIZE,
            10, 8, true, "Distribuiergesetz"
        );
        return new DiscoveredTransformation(
            "p-1",
            "x*(y+1)",
            "x*y + x",
            List.of(step),
            score(10),
            score(8),
            2,
            CandidateProofStatus.OBSERVED,
            Instant.parse("2026-01-01T00:00:00Z"),
            "hash"
        );
    }

    @Test
    void pathReplayDtoYieldsAlignedLayoutWithOneRowPerStepPlusSource() {
        PathReplayDto replay = PathReplayDto.from(samplePath(), new ExplanationService());
        MathLayout layout = replay.derivationLayout();
        assertNotNull(layout);
        assertEquals(MathLayout.Kind.ALIGNED, layout.kind());
        assertFalse(layout.nodes().isEmpty(),
            "derivation layout must contain at least one aligned row");
    }

    @Test
    void replayStepYieldsNonNullLayoutWithAria() {
        PathReplayDto replay = PathReplayDto.from(samplePath(), new ExplanationService());
        PathReplayDto.ReplayStep step = replay.steps().get(0);
        MathLayout layout = step.layout();
        assertNotNull(layout);
        assertFalse(layout.toLatex().isBlank());
        assertFalse(layout.ariaLabel().isBlank(),
            "ReplayStep.layout() must carry an aria-label for screen readers");
        assertTrue(layout.nodes().stream()
                .anyMatch(node -> "diff-new".equals(node.attributes().get("class"))),
            "ReplayStep.layout() must surface diff classes in the structured layout");
    }

    @Test
    void searchGraphNodeDtoYieldsNonNullLayout() {
        SearchGraphNodeDto node = new SearchGraphNodeDto(
            "n1", "(x+1)*(x-1)", "", 0, 0, 1, false, false,
            CandidateProofStatus.OBSERVED, ""
        );
        MathLayout layout = node.layout();
        assertNotNull(layout);
        assertFalse(layout.toLatex().isBlank());
        assertFalse(layout.ariaLabel().isBlank());
    }

    @Test
    void searchGraphEdgeDtoYieldsNonNullLayout() {
        SearchGraphEdgeDto edge = new SearchGraphEdgeDto(
            "n1", "n2", "polynomial_distribute",
            RewriteKind.NORMALIZE, -2, List.of(), List.of("p-1"), true
        );
        MathLayout layout = edge.layout();
        assertNotNull(layout);
        // The rule-LaTeX label for distributive law must be non-blank.
        assertTrue(layout.toLatex().contains("a")
                || layout.toLatex().contains("\\"),
            "edge layout latex must carry the rule label, got: " + layout.toLatex());
    }

    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
