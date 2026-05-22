package de.regelsuche.export;

import java.util.Map;
import java.util.Objects;

/**
 * Central math-presentation helper. The <strong>only</strong> sanctioned
 * source of LaTeX strings for the Web-Workbench: every DTO and service
 * that surfaces a mathematical expression to the UI, the export bundle,
 * the proof panel, the didactic views or the demo gallery is expected
 * to route through this helper.
 *
 * <p>Internally delegates to {@link AstLatexRenderer}. Hand-rolled
 * substitutions such as {@code expression.replace("*", " \\cdot ")}
 * are explicitly out of scope and pinned down by
 * {@code NoAdHocOperatorReplacementsTest}.</p>
 *
 * <p>The helper additionally exposes light-weight presentation utilities
 * for rule labels (used as search-graph edge captions and replay arrow
 * labels) so that the same display style is reused across every panel.</p>
 */
public final class MathPresentation {

    /** Default, thread-safe instance suitable for sharing across callers. */
    public static final MathPresentation DEFAULT = new MathPresentation();

    private static final Map<String, String> RULE_LATEX_LABELS = Map.ofEntries(
        Map.entry("inequality_multiply_both_sides", "\\cdot c"),
        Map.entry("inequality_divide_both_sides", "\\div c"),
        Map.entry("calculus_power_rule", "\\tfrac{d}{dx}\\,x^{n}"),
        Map.entry("calculus_sum_rule", "\\tfrac{d}{dx}(f+g)"),
        Map.entry("calculus_product_rule", "\\tfrac{d}{dx}(fg)"),
        Map.entry("calculus_chain_rule", "\\tfrac{d}{dx}f(g(x))"),
        Map.entry("trig_pythagoras", "\\sin^2+\\cos^2=1"),
        Map.entry("rational_cancel_common_factor", "\\frac{ax}{bx}\\to\\frac{a}{b}"),
        Map.entry("polynomial_distribute", "a(b+c)\\to ab+ac"),
        Map.entry("polynomial_collect_like_terms", "ax+bx\\to(a+b)x")
    );

    private final AstLatexRenderer renderer;

    public MathPresentation() {
        this(new AstLatexRenderer());
    }

    public MathPresentation(AstLatexRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /**
     * Renders an expression as a LaTeX string suitable for either
     * {@code $...$} (inline) or {@code $$...$$} (display) embedding.
     * Returns the empty string for {@code null} / blank input so callers
     * can safely propagate the value into DTOs without further null
     * checks.
     */
    public String latex(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        return renderer.renderExpression(expression);
    }

    /**
     * Convenience: wraps a LaTeX body in the inline-math delimiters that
     * the front-end {@code renderMath(root)} helper looks for. Returns
     * an empty string for blank input.
     */
    public String inlineMath(String latex) {
        if (latex == null || latex.isBlank()) {
            return "";
        }
        return "$" + latex + "$";
    }

    /**
     * Renders a rule id as a compact LaTeX label suitable for search-graph
     * edge captions and {@code \xrightarrow{\text{...}}} step arrows in
     * derivations. Falls back to a humanised text representation when the
     * rule id is not in the curated label table.
     */
    public String ruleLatex(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            return "";
        }
        String mapped = RULE_LATEX_LABELS.get(ruleId);
        if (mapped != null) {
            return mapped;
        }
        // Generic fallback: render the rule id as {\text{...}} so KaTeX
        // typesets it in a math-aware way without trying to parse the
        // identifier itself.
        String humanised = ruleId
            .replace("ast_", "")
            .replace('_', ' ');
        return "\\text{" + humanised + "}";
    }
}
