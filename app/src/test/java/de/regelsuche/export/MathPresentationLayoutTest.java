package de.regelsuche.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.layout.MathLayout;
import de.regelsuche.export.layout.MathLayoutNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stage 5 contract: {@link MathPresentation#layout(String)} produces a
 * structured {@link MathLayout} whose {@link MathLayout#toLatex()}
 * matches the legacy string-only {@link MathPresentation#latex(String)}
 * output, and {@link MathPresentation#derivationLayout(java.util.List)}
 * yields one aligned row per step + the source row.
 */
class MathPresentationLayoutTest {

    private final MathPresentation math = MathPresentation.DEFAULT;

    @Test
    void layoutToLatexMatchesLatexString() {
        String expr = "(x+1)*(x-1)";
        MathLayout layout = math.layout(expr);
        assertNotNull(layout);
        assertEquals(math.latex(expr), layout.toLatex(),
            "layout(expr).toLatex() must round-trip to latex(expr)");
        assertFalse(layout.ariaLabel().isBlank(),
            "layout must carry a non-blank aria-label derived from the AST");
    }

    @Test
    void derivationLayoutHasOneAlignedRowPerStepPlusSource() {
        List<MathPresentation.DerivationStep> steps = List.of(
            new MathPresentation.DerivationStep("x+0", "x", "identity_add_zero"),
            new MathPresentation.DerivationStep("x", "1\\cdot x", "identity_multiply_one")
        );
        MathLayout layout = math.derivationLayout(steps);
        assertEquals(MathLayout.Kind.ALIGNED, layout.kind());
        long rows = layout.nodes().stream()
            .filter(n -> n.kind() == MathLayoutNode.Kind.ALIGNED_ROW)
            .count();
        assertEquals(steps.size() + 1, rows,
            "derivation layout must contain one source row + one row per step");
    }

    @Test
    void derivationLayoutToLatexParallelsAlignedDerivationLatex() {
        List<MathPresentation.DerivationStep> steps = List.of(
            new MathPresentation.DerivationStep("x", "2x", "polynomial_collect_like_terms")
        );
        MathLayout layout = math.derivationLayout(steps);
        String legacy = math.alignedDerivationLatex(steps);
        String fromLayout = layout.toLatex();
        // Both must be \begin{aligned} blocks with the same row count.
        assertTrue(legacy.startsWith("\\begin{aligned}") && fromLayout.startsWith("\\begin{aligned}"));
        long legacyBreaks = legacy.lines().filter(l -> l.contains("\\\\")).count();
        long layoutBreaks = fromLayout.lines().filter(l -> l.contains("\\\\")).count();
        assertEquals(legacyBreaks, layoutBreaks,
            "layout-derived aligned block must have the same row count "
                + "as the legacy alignedDerivationLatex output");
    }

    @Test
    void derivationLayoutCarriesDiffClassWhenStepHasChangedSpans() {
        // A step with a non-empty changedToSpans must surface a layout
        // fragment carrying the `diff-new` CSS class so the front-end
        // can colour the row without needing KaTeX trust mode.
        MathPresentation.DerivationStep step = new MathPresentation.DerivationStep(
            "x+1", "x+2", "polynomial_collect_like_terms");
        MathLayout layout = math.derivationLayout(List.of(step));
        boolean anyDiffNew = layout.nodes().stream()
            .flatMap(row -> row.children().stream())
            .anyMatch(n -> "diff-new".equals(n.attributes().get("class")));
        assertTrue(anyDiffNew,
            "layout fragment must carry diff-new class when changedToSpans is non-empty");
    }

    @Test
    void emptyDerivationLayoutIsEmptyAlignedBlock() {
        MathLayout layout = math.derivationLayout(List.of());
        assertEquals(MathLayout.Kind.ALIGNED, layout.kind());
        assertTrue(layout.nodes().isEmpty());
        assertEquals("", layout.toLatex());
    }
}
