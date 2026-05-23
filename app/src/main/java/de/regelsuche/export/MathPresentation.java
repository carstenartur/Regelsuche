package de.regelsuche.export;

import de.regelsuche.export.layout.AstAriaRenderer;
import de.regelsuche.export.layout.AstDiff;
import de.regelsuche.export.layout.MathLayout;
import de.regelsuche.export.layout.MathLayoutNode;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
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
    private final ExpressionParser parser;

    public MathPresentation() {
        this(new AstLatexRenderer(), new ExpressionParser());
    }

    public MathPresentation(AstLatexRenderer renderer) {
        this(renderer, new ExpressionParser());
    }

    public MathPresentation(AstLatexRenderer renderer, ExpressionParser parser) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.parser = Objects.requireNonNull(parser, "parser");
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
     *
     * <p>Stage 3 additions:
     * <ul>
     *   <li>{@link #comparatorFlipped()} — set to {@code true} when the
     *       transition flipped an inequality comparator (e.g. {@code <}
     *       became {@code >}) and the rule id is one of the
     *       {@code inequality_multiply_both_sides} /
     *       {@code inequality_divide_both_sides} pair.</li>
     *   <li>{@link #changedFromSpans()} / {@link #changedToSpans()} —
     *       token-level diff spans (start offset + length, both in code
     *       points) over {@code fromLatex} / {@code toLatex} respectively.
     *       Used by {@link MathPresentation#alignedDerivationLatexWithDiff(List)}
     *       to wrap changed tokens in {@code \htmlClass{diff-old|diff-new}{…}}.</li>
     * </ul>
     *
     * <p>Stage 5 addition:
     * <ul>
     *   <li>{@link #toExpression()} — the raw (non-LaTeX) target expression, used
     *       by {@link MathPresentation#derivationLayout(List)} to build a
     *       screen-reader-friendly {@code aria-label} via
     *       {@link de.regelsuche.export.layout.AstAriaRenderer}. Empty string when
     *       not available (e.g. callers using the legacy 3-arg constructor).</li>
     * </ul>
     */
    public record DerivationStep(
        String fromLatex,
        String toLatex,
        String ruleId,
        boolean comparatorFlipped,
        List<int[]> changedFromSpans,
        List<int[]> changedToSpans,
        String toExpression
    ) {
        public DerivationStep {
            fromLatex = fromLatex == null ? "" : fromLatex;
            toLatex = toLatex == null ? "" : toLatex;
            ruleId = ruleId == null ? "" : ruleId;
            changedFromSpans = changedFromSpans == null
                ? List.of() : List.copyOf(changedFromSpans);
            changedToSpans = changedToSpans == null
                ? List.of() : List.copyOf(changedToSpans);
            toExpression = toExpression == null ? "" : toExpression;
        }

        /**
         * Backward-compatible 6-arg constructor used by callers that pre-date
         * the Stage 5 expression-for-aria addition. Sets {@code toExpression}
         * to empty string; aria labels will be omitted for such steps.
         */
        public DerivationStep(
            String fromLatex,
            String toLatex,
            String ruleId,
            boolean comparatorFlipped,
            List<int[]> changedFromSpans,
            List<int[]> changedToSpans
        ) {
            this(fromLatex, toLatex, ruleId, comparatorFlipped,
                changedFromSpans, changedToSpans, "");
        }

        /**
         * Backward-compatible 3-arg constructor used by existing callers
         * (and by the codec readers that pre-date the diff annotations).
         * Computes the diff spans from the from/to LaTeX strings and the
         * comparator-flip flag from the rule id and the from/to texts.
         */
        public DerivationStep(String fromLatex, String toLatex, String ruleId) {
            this(
                fromLatex == null ? "" : fromLatex,
                toLatex == null ? "" : toLatex,
                ruleId == null ? "" : ruleId,
                detectComparatorFlip(ruleId, fromLatex, toLatex),
                MathDiff.diffSpans(fromLatex == null ? "" : fromLatex,
                    toLatex == null ? "" : toLatex).fromSpans(),
                MathDiff.diffSpans(fromLatex == null ? "" : fromLatex,
                    toLatex == null ? "" : toLatex).toSpans(),
                ""
            );
        }
    }

    /**
     * Stage 3 — comparator-flip heuristic moved server-side from the
     * legacy front-end check. Returns {@code true} when the rule id is
     * one of the inequality multiply/divide pair AND the from/to texts
     * each contain a comparator AND those comparators have opposite
     * directions (e.g. {@code <} became {@code >} or {@code \le} became
     * {@code \ge}).
     */
    public static boolean detectComparatorFlip(String ruleId, String fromText, String toText) {
        if (ruleId == null) {
            return false;
        }
        boolean rulePair = ruleId.equals("inequality_multiply_both_sides")
            || ruleId.equals("inequality_divide_both_sides");
        if (!rulePair) {
            return false;
        }
        if (fromText == null || toText == null) {
            return false;
        }
        int fromDir = comparatorDirection(fromText);
        int toDir = comparatorDirection(toText);
        return fromDir != 0 && toDir != 0 && fromDir != toDir;
    }

    /** +1 for {@code <}/{@code \le}, -1 for {@code >}/{@code \ge}, 0 otherwise. */
    private static int comparatorDirection(String text) {
        if (text == null) {
            return 0;
        }
        // Prefer LaTeX comparator macros when present; fall back to ASCII.
        if (text.contains("\\le") || text.contains("\\leq") || text.contains("\\lt")) {
            return +1;
        }
        if (text.contains("\\ge") || text.contains("\\geq") || text.contains("\\gt")) {
            return -1;
        }
        int firstLt = text.indexOf('<');
        int firstGt = text.indexOf('>');
        if (firstLt >= 0 && (firstGt < 0 || firstLt < firstGt)) {
            return +1;
        }
        if (firstGt >= 0 && (firstLt < 0 || firstGt < firstLt)) {
            return -1;
        }
        return 0;
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

    /**
     * Stage 3 — same aligned block as {@link #alignedDerivationLatex(List)},
     * but with changed substrings of every step's {@code toLatex} (and
     * the very first {@code fromLatex}) wrapped in
     * {@code \htmlClass{diff-old}{…}} / {@code \htmlClass{diff-new}{…}}
     * markers. KaTeX trust mode is already enabled in the front-end
     * pipeline so these classes survive into the rendered DOM and pick
     * up the CSS tokens emitted by {@code style.css}.
     *
     * <p>Returns the same plain block as
     * {@link #alignedDerivationLatex(List)} when no diff spans are
     * present so callers do not need to special-case the empty-diff
     * case.</p>
     */
    @Deprecated(forRemoval = false)
    public String alignedDerivationLatexWithDiff(List<DerivationStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(96 + steps.size() * 48);
        out.append("\\begin{aligned}\n");
        // The first row carries the source expression. We only know what
        // "changed" once a transition is applied, so the very first row
        // shows the unmodified fromLatex of step 0.
        out.append(steps.get(0).fromLatex());
        for (DerivationStep step : steps) {
            out.append(" \\\\\n");
            String arrow = ruleLatex(step.ruleId());
            if (arrow.isEmpty()) {
                out.append("&\\rightarrow ");
            } else {
                out.append("&\\xrightarrow{").append(arrow).append("} ");
            }
            out.append(wrapDiff(step.toLatex(), step.changedToSpans(), "diff-new"));
        }
        out.append("\n\\end{aligned}");
        return out.toString();
    }

    /**
     * Wraps the byte ranges in {@code spans} of {@code latex} with
     * {@code \htmlClass{<cssClass>}{…}}. Spans are interpreted as a
     * sequence of half-open intervals over the {@code latex} string;
     * out-of-range or overlapping spans are coalesced/clipped.
     */
    static String wrapDiff(String latex, List<int[]> spans, String cssClass) {
        if (latex == null || latex.isEmpty() || spans == null || spans.isEmpty()) {
            return latex == null ? "" : latex;
        }
        java.util.List<int[]> norm = new java.util.ArrayList<>(spans.size());
        for (int[] span : spans) {
            if (span == null || span.length < 2) {
                continue;
            }
            int start = Math.max(0, span[0]);
            int length = Math.max(0, span[1]);
            int end = Math.min(latex.length(), start + length);
            if (end <= start) {
                continue;
            }
            norm.add(new int[] { start, end });
        }
        if (norm.isEmpty()) {
            return latex;
        }
        norm.sort((a, b) -> Integer.compare(a[0], b[0]));
        // Coalesce overlapping spans so the rendered LaTeX stays valid.
        java.util.List<int[]> merged = new java.util.ArrayList<>(norm.size());
        int[] current = norm.get(0).clone();
        for (int i = 1; i < norm.size(); i++) {
            int[] next = norm.get(i);
            if (next[0] <= current[1]) {
                current[1] = Math.max(current[1], next[1]);
            } else {
                merged.add(current);
                current = next.clone();
            }
        }
        merged.add(current);
        StringBuilder out = new StringBuilder(latex.length() + merged.size() * (cssClass.length() + 16));
        int cursor = 0;
        for (int[] span : merged) {
            if (span[0] > cursor) {
                out.append(latex, cursor, span[0]);
            }
            out.append("\\htmlClass{").append(cssClass).append("}{")
                .append(latex, span[0], span[1]).append("}");
            cursor = span[1];
        }
        if (cursor < latex.length()) {
            out.append(latex, cursor, latex.length());
        }
        return out.toString();
    }

    /**
     * Stage 5 — produces a structured {@link MathLayout} for a single
     * expression. By default returns a single-fragment inline layout
     * whose {@link MathLayoutNode#toLatex()} round-trips to
     * {@link #latex(String)}, plus an ARIA label derived from the raw
     * expression via {@link AstAriaRenderer}.
     */
    public MathLayout layout(String expression) {
        String latex = latex(expression);
        String aria = AstAriaRenderer.ariaLabel(expression);
        return MathLayout.fromLatexFragment(latex, aria);
    }

    public MathLayout layoutWithDiff(
        String fromExpression,
        String toExpression,
        String fallbackLatex,
        List<int[]> fallbackSpans
    ) {
        String latex = (fallbackLatex == null || fallbackLatex.isBlank()) ? latex(toExpression) : fallbackLatex;
        String aria = AstAriaRenderer.ariaLabel(toExpression);
        AstDiff.Result diff = astDiff(fromExpression, toExpression);
        if (diff != null && !diff.toNodes().isEmpty()) {
            return new MathLayout(MathLayout.Kind.INLINE, diff.toNodes(), aria);
        }
        return new MathLayout(
            MathLayout.Kind.INLINE,
            List.of(MathLayoutNode.fragment(latex, hasSpans(fallbackSpans) ? "diff-new" : null)),
            aria
        );
    }

    /**
     * Stage 5 — structured aligned-derivation layout. Each step
     * becomes one {@link MathLayoutNode.Kind#ALIGNED_ROW} composed of
     * a from-fragment + arrow-label + to-fragment. Diff spans from the
     * step's {@code changedFromSpans} / {@code changedToSpans} are
     * surfaced as {@code "diff-old"} / {@code "diff-new"} CSS classes
     * on the corresponding fragment nodes, so the front-end can render
     * colour-diff highlights as DOM attributes (no KaTeX trust mode
     * required for diffs in the layout-aware path).
     */
    public MathLayout derivationLayout(List<DerivationStep> steps) {
        return alignedDerivationLayoutWithDiff(steps);
    }

    public MathLayout alignedDerivationLayoutWithDiff(List<DerivationStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return new MathLayout(MathLayout.Kind.ALIGNED, List.of(), "");
        }
        List<MathLayoutNode> rows = new ArrayList<>(steps.size() + 1);
        rows.add(MathLayoutNode.alignedRow(List.of(
            MathLayoutNode.fragment(
                steps.get(0).fromLatex(),
                hasSpans(steps.get(0).changedFromSpans()) ? "diff-old" : null)
        )));
        String previousExpression = "";
        for (DerivationStep step : steps) {
            List<MathLayoutNode> rowChildren = new ArrayList<>(2);
            rowChildren.add(MathLayoutNode.arrowLabel(ruleLatex(step.ruleId())));
            AstDiff.Result diff = astDiff(previousExpression, step.toExpression());
            if (diff != null && !diff.toNodes().isEmpty()) {
                rowChildren.addAll(diff.toNodes());
            } else {
                rowChildren.add(MathLayoutNode.fragment(
                    step.toLatex(), hasSpans(step.changedToSpans()) ? "diff-new" : null));
            }
            rows.add(MathLayoutNode.alignedRow(rowChildren));
            previousExpression = step.toExpression();
        }
        StringBuilder aria = new StringBuilder();
        for (DerivationStep step : steps) {
            String expr = step.toExpression();
            if (!expr.isEmpty()) {
                aria.append(AstAriaRenderer.ariaLabel(expr)).append("; ");
            }
        }
        return new MathLayout(MathLayout.Kind.ALIGNED, rows, aria.toString().trim());
    }

    private AstDiff.Result astDiff(String fromExpression, String toExpression) {
        if (fromExpression == null || fromExpression.isBlank()
            || toExpression == null || toExpression.isBlank()) {
            return null;
        }
        try {
            return AstDiff.diff(parser.parseTerm(fromExpression), parser.parseTerm(toExpression), renderer);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean hasSpans(List<int[]> spans) {
        return spans != null && !spans.isEmpty();
    }
}
