package de.regelsuche.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.regelsuche.api.searchgraph.SearchGraphEdgeDto;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.explain.ExplanationService;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stage 1 pin: every DTO that ferries a mathematical expression from the
 * backend to the UI must carry a non-null {@code latex} field rendered
 * through {@link de.regelsuche.export.MathPresentation}. This protects
 * against regressions where new DTO fields are added that silently emit
 * AST-plaintext only.
 */
class AllMathDtosCarryLatexTest {

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
    void pathReplayDtoCarriesLatexForEveryStep() {
        DiscoveredTransformation path = samplePath();
        PathReplayDto replay = PathReplayDto.from(path, new ExplanationService());
        assertFalse(replay.steps().isEmpty(), "expected at least one replay step");
        for (PathReplayDto.ReplayStep step : replay.steps()) {
            assertNotNull(step.fromLatex(), "fromLatex must not be null");
            assertNotNull(step.toLatex(), "toLatex must not be null");
            assertFalse(step.fromLatex().isBlank(), "fromLatex must not be blank for " + step.fromExpression());
            assertFalse(step.toLatex().isBlank(), "toLatex must not be blank for " + step.toExpression());
        }
    }

    @Test
    void searchGraphEdgeDtoCarriesRuleLatex() {
        SearchGraphEdgeDto edge = new SearchGraphEdgeDto(
            "n1", "n2", "inequality_divide_both_sides",
            RewriteKind.NORMALIZE, -2, List.of(), List.of("p-1"), true
        );
        assertNotNull(edge.ruleLatex());
        assertFalse(edge.ruleLatex().isBlank(),
            "backward-compat ctor must populate ruleLatex from MathPresentation");
    }

    @Test
    void searchGraphEdgeDtoRoundTripsRuleLatexThroughCanonicalCtor() {
        SearchGraphEdgeDto edge = new SearchGraphEdgeDto(
            "n1", "n2", "custom_rule", "\\xrightarrow{\\text{custom}}",
            RewriteKind.NORMALIZE, 0, List.of(), List.of(), true
        );
        assertEquals("\\xrightarrow{\\text{custom}}", edge.ruleLatex());
    }

    @Test
    void pathReplayDtoCarriesAlignedDerivationLatex() {
        DiscoveredTransformation path = samplePath();
        PathReplayDto replay = PathReplayDto.from(path, new ExplanationService());
        assertNotNull(replay.alignedDerivationLatex());
        assertFalse(replay.alignedDerivationLatex().isBlank(),
            "Stage 2: every replay DTO must carry an aligned derivation block");
        org.junit.jupiter.api.Assertions.assertTrue(
            replay.alignedDerivationLatex().contains("\\begin{aligned}"),
            "expected \\begin{aligned} in: " + replay.alignedDerivationLatex());
        org.junit.jupiter.api.Assertions.assertTrue(
            replay.alignedDerivationLatex().contains("\\end{aligned}"),
            "expected \\end{aligned} in: " + replay.alignedDerivationLatex());
    }
}
