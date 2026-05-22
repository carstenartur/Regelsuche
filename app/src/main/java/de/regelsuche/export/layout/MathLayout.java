package de.regelsuche.export.layout;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stage 5 — structured layout description for a mathematical
 * expression or derivation. Replaces the string-only LaTeX surface so
 * the front-end and exports can apply consistent spacing, line-
 * breaking and accessibility metadata without re-parsing LaTeX.
 *
 * <p>A {@code MathLayout} consists of a {@link Kind kind} (inline /
 * display / aligned), an ordered list of {@link MathLayoutNode nodes}
 * and an optional {@code ariaLabel} derived from the AST that
 * front-end renderers inject as {@code aria-label} on the host element
 * for screen-reader accessibility.</p>
 *
 * <p>{@link #toLatex()} re-emits a plain LaTeX string equivalent to
 * the legacy {@link de.regelsuche.export.MathPresentation#latex(String)}
 * output so existing string-only callers (codecs, exports, KaTeX
 * fallback) keep working unchanged.</p>
 */
public record MathLayout(
    Kind kind,
    List<MathLayoutNode> nodes,
    String ariaLabel
) {

    public MathLayout {
        kind = kind == null ? Kind.INLINE : kind;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        ariaLabel = ariaLabel == null ? "" : ariaLabel;
    }

    public enum Kind {
        INLINE,
        DISPLAY,
        ALIGNED
    }

    /**
     * Re-emits a plain LaTeX string equivalent to the legacy
     * {@link de.regelsuche.export.MathPresentation#latex(String)} or
     * {@link de.regelsuche.export.MathPresentation#alignedDerivationLatex(java.util.List)}
     * output, depending on {@link #kind()}. The string returned never
     * carries the surrounding {@code $…$} / {@code $$…$$} delimiters
     * (callers wrap as needed).
     */
    public String toLatex() {
        if (kind == Kind.ALIGNED) {
            // The aligned representation pre-stitches rows into a
            // single `\begin{aligned}…\end{aligned}` block.
            return alignedToLatex();
        }
        StringBuilder out = new StringBuilder();
        for (MathLayoutNode node : nodes) {
            out.append(node.toLatex());
        }
        return out.toString();
    }

    private String alignedToLatex() {
        if (nodes.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\\begin{aligned}\n");
        boolean first = true;
        for (MathLayoutNode node : nodes) {
            if (node.kind() != MathLayoutNode.Kind.ALIGNED_ROW) {
                continue;
            }
            if (!first) {
                out.append(" \\\\\n");
            }
            first = false;
            out.append(node.toLatex());
        }
        out.append("\n\\end{aligned}");
        return out.toString();
    }

    /**
     * Helper to assemble a single-fragment inline layout from raw LaTeX.
     * Used by {@link de.regelsuche.export.MathPresentation#layout(String)}
     * as the default behaviour when the AST renderer is consulted.
     */
    public static MathLayout fromLatexFragment(String latex, String aria) {
        Objects.requireNonNull(latex, "latex");
        return new MathLayout(
            Kind.INLINE,
            List.of(MathLayoutNode.fragment(latex)),
            aria
        );
    }

    /**
     * Compact JSON-ready map representation. Codecs use this to emit
     * a small `{kind, nodes:[…], aria}` JSON object alongside the
     * legacy `…Latex` string field.
     */
    public Map<String, Object> toMap() {
        return Map.of(
            "kind", kind.name(),
            "nodes", nodes.stream().map(MathLayoutNode::toMap).toList(),
            "aria", ariaLabel
        );
    }
}
