package de.regelsuche.export.layout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 5 — one structural element of a {@link MathLayout}. The kind
 * tag is intentionally narrow:
 * <ul>
 *   <li>{@link Kind#FRAGMENT} — a leaf LaTeX string,</li>
 *   <li>{@link Kind#OPERATOR} — an operator/separator hint,</li>
 *   <li>{@link Kind#BREAK_HINT} — soft line-break suggestion for the
 *       front-end's CSS-grid renderer,</li>
 *   <li>{@link Kind#ALIGNED_ROW} — a row inside an aligned derivation
 *       (carries its own ordered list of nested children),</li>
 *   <li>{@link Kind#ARROW_LABEL} — derivation arrow caption that the
 *       front-end can render between successive rows.</li>
 * </ul>
 *
 * <p>For Stage 3 colour-diff highlighting the {@code attributes} map
 * can carry a {@code class} entry (typically {@code "diff-old"} or
 * {@code "diff-new"}). The Stage 5 frontend renderer emits these as
 * DOM attributes on the corresponding span, so KaTeX trust mode is no
 * longer required for diffs (it is still required for the legacy
 * Stage-3 string fallback).</p>
 */
public record MathLayoutNode(
    Kind kind,
    String text,
    List<MathLayoutNode> children,
    Map<String, String> attributes
) {

    public MathLayoutNode {
        kind = kind == null ? Kind.FRAGMENT : kind;
        text = text == null ? "" : text;
        children = children == null ? List.of() : List.copyOf(children);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public enum Kind {
        FRAGMENT,
        OPERATOR,
        BREAK_HINT,
        ALIGNED_ROW,
        ARROW_LABEL
    }

    public static MathLayoutNode fragment(String latex) {
        return new MathLayoutNode(Kind.FRAGMENT, latex, List.of(), Map.of());
    }

    public static MathLayoutNode fragment(String latex, String cssClass) {
        return new MathLayoutNode(
            Kind.FRAGMENT, latex, List.of(),
            cssClass == null || cssClass.isBlank() ? Map.of() : Map.of("class", cssClass)
        );
    }

    public static MathLayoutNode arrowLabel(String latex) {
        return new MathLayoutNode(Kind.ARROW_LABEL, latex, List.of(), Map.of());
    }

    public static MathLayoutNode alignedRow(List<MathLayoutNode> children) {
        return new MathLayoutNode(Kind.ALIGNED_ROW, "", children, Map.of());
    }

    /**
     * Re-emit this node as a plain LaTeX fragment. Aligned rows render
     * as {@code <first-fragment> &<arrow-label-prefix> <rest>} so the
     * {@link MathLayout#toLatex()} output stays compatible with the
     * legacy {@link de.regelsuche.export.MathPresentation#alignedDerivationLatex(java.util.List)}
     * output for the existing tests.
     */
    public String toLatex() {
        return switch (kind) {
            case FRAGMENT, OPERATOR -> text;
            case BREAK_HINT -> " ";
            case ARROW_LABEL -> text.isEmpty()
                ? "&\\rightarrow "
                : "&\\xrightarrow{" + text + "} ";
            case ALIGNED_ROW -> {
                StringBuilder out = new StringBuilder();
                for (MathLayoutNode child : children) {
                    out.append(child.toLatex());
                }
                yield out.toString();
            }
        };
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind.name());
        if (!text.isEmpty()) {
            out.put("text", text);
        }
        if (!children.isEmpty()) {
            out.put("children", children.stream().map(MathLayoutNode::toMap).toList());
        }
        if (!attributes.isEmpty()) {
            out.put("attributes", attributes);
        }
        return out;
    }
}
