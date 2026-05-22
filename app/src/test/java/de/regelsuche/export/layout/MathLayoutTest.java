package de.regelsuche.export.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MathLayoutTest {

    @Test
    void inlineLayoutToLatexRoundTripsToInputFragment() {
        MathLayout layout = MathLayout.fromLatexFragment("a + b", "a plus b");
        assertEquals("a + b", layout.toLatex());
        assertEquals(MathLayout.Kind.INLINE, layout.kind());
        assertEquals(1, layout.nodes().size());
        assertEquals("a plus b", layout.ariaLabel());
    }

    @Test
    void emptyAlignedLayoutToLatexIsBlank() {
        MathLayout layout = new MathLayout(MathLayout.Kind.ALIGNED, List.of(), "");
        assertEquals("", layout.toLatex());
    }

    @Test
    void alignedLayoutEmitsBeginAlignedBlockWithRowSeparators() {
        MathLayoutNode row1 = MathLayoutNode.alignedRow(List.of(
            MathLayoutNode.fragment("x")
        ));
        MathLayoutNode row2 = MathLayoutNode.alignedRow(List.of(
            MathLayoutNode.arrowLabel("\\cdot 2"),
            MathLayoutNode.fragment("2x", "diff-new")
        ));
        MathLayout layout = new MathLayout(
            MathLayout.Kind.ALIGNED, List.of(row1, row2), "x mal 2");
        String latex = layout.toLatex();
        assertTrue(latex.startsWith("\\begin{aligned}"), latex);
        assertTrue(latex.endsWith("\\end{aligned}"), latex);
        assertTrue(latex.contains("\\\\"), "rows must be separated by \\\\");
        assertTrue(latex.contains("\\xrightarrow{\\cdot 2}"), latex);
    }

    @Test
    void diffClassIsCarriedAsAttributeNotHtmlClassWrapper() {
        MathLayoutNode frag = MathLayoutNode.fragment("2x", "diff-new");
        assertEquals("diff-new", frag.attributes().get("class"),
            "Stage 5: diff CSS class lives on the layout node as an attribute,"
                + " not as an inline \\htmlClass{…} wrapper");
        // The bare LaTeX output must not contain the \htmlClass wrapper
        // so frontends that consume the structured layout can skip
        // KaTeX trust mode for diffs.
        assertEquals("2x", frag.toLatex());
    }

    @Test
    void ariaLabelReplacesOperatorSymbols() {
        assertEquals("x plus y", AstAriaRenderer.ariaLabel("x + y"));
        assertEquals("x gleich 2 mal y", AstAriaRenderer.ariaLabel("x = 2 * y"));
    }

    @Test
    void toMapIncludesKindNodesAndAria() {
        MathLayout layout = MathLayout.fromLatexFragment("x", "x");
        var map = layout.toMap();
        assertNotNull(map.get("kind"));
        assertEquals("INLINE", map.get("kind"));
        assertNotNull(map.get("nodes"));
        assertFalse(((java.util.List<?>) map.get("nodes")).isEmpty());
        assertEquals("x", map.get("aria"));
    }
}
