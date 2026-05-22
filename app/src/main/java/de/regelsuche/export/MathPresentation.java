package de.regelsuche.export;

import java.util.List;
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

    /**
     * Step in an aligned derivation block: the (already LaTeX-rendered)
     * left- and right-hand sides plus the rule id that produced the
     * transition. The {@code fromLatex} of step <em>n+1</em> is expected
     * to match the {@code toLatex} of step <em>n</em>; the renderer
     * only emits the very first {@code fromLatex} and then chains the
     * {@code toLatex} of each subsequent step underneath.
     */
    public record DerivationStep(String fromLatex, String toLatex, String ruleId) {
        public DerivationStep {
            fromLatex = fromLatex == null ? "" : fromLatex;
            toLatex = toLatex == null ? "" : toLatex;
            ruleId = ruleId == null ? "" : ruleId;
        }
    }

    /**
     * Renders a list of {@link DerivationStep}s as a single
     * {@code \begin{aligned} … \end{aligned}} block with
     * {@code \xrightarrow{\text{rule}}} arrows between rows, matching the
     * derivation style used in {@code docs/demo-gallery.md}.
     *
     * <p>The first row carries the source expression; each subsequent
     * row starts with an alignment ampersand and the labelled arrow,
     * followed by the new right-hand side. Returns the empty string if
     * the input is {@code null} or empty so callers can safely embed the
     * result in a DTO without additional guards.</p>
     */
    public String alignedDerivationLatex(List<DerivationStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(64 + steps.size() * 32);
        out.append("\\begin{aligned}\n");
        out.append(steps.get(0).fromLatex());
        for (DerivationStep step : steps) {
            out.append(" \\\\\n");
            String arrow = ruleLatex(step.ruleId());
            if (arrow.isEmpty()) {
                out.append("&\\rightarrow ");
            } else {
                out.append("&\\xrightarrow{").append(arrow).append("} ");
            }
            out.append(step.toLatex());
        }
        out.append("\n\\end{aligned}");
        return out.toString();
    }
}
