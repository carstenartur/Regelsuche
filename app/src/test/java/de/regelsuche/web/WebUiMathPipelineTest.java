package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Static-file pin for the Stage 1 math-rendering pipeline in the web UI.
 *
 * <p>The Web-Workbench must route every mathematical expression through a
 * single {@code renderMath(root)} entry point and load <a
 * href="https://katex.org/">KaTeX</a> as the default typesetter (MathJax
 * remains loaded as a fallback). Previously the UI emitted
 * {@code <span class="latex">$...$</span>} placeholders that nothing ever
 * typeset; this test prevents that regression from sneaking back in.</p>
 */
class WebUiMathPipelineTest {

    private static Path locateAppJs() {
        Path[] candidates = {
            Path.of("src", "main", "resources", "web", "app.js"),
            Path.of("app", "src", "main", "resources", "web", "app.js")
        };
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) {
                return c;
            }
        }
        return null;
    }

    @Test
    void appJsLoadsKatexAndDefinesRenderMath() throws IOException {
        Path appJs = locateAppJs();
        if (appJs == null) {
            return;
        }
        String content = Files.readString(appJs);
        assertTrue(content.contains("katex"),
            "app.js must load KaTeX from a CDN");
        assertTrue(content.contains("auto-render"),
            "app.js must load the KaTeX auto-render extension");
        assertTrue(content.contains("renderMath"),
            "app.js must expose a central renderMath() helper");
        assertTrue(content.contains("MathJax"),
            "MathJax fallback must still be wired up");
    }

    @Test
    void appJsNoLongerEmitsLegacyLatexPlaceholders() throws IOException {
        Path appJs = locateAppJs();
        if (appJs == null) {
            return;
        }
        String content = Files.readString(appJs);
        // The legacy emission style was <span class="latex">$...$</span> /
        // <div class="latex">$...$</div> that nothing ever typeset. All
        // such call sites must now emit <span class="math" data-math="..">.
        assertFalse(content.contains("class=\"latex\">$"),
            "Legacy <span class=\"latex\">$...$</span> placeholders are no longer allowed; "
                + "use class=\"math\" with a data-math attribute and call renderMath() on the container.");
        assertTrue(content.contains("class=\"math\""),
            "Math-bearing nodes must use the .math marker so renderMath() picks them up");
        assertTrue(content.contains("data-math"),
            "Math-bearing nodes must carry their raw LaTeX in data-math for the CDN-failure fallback");
    }

    /**
     * Stage 2 pin: the replay panel must render the whole derivation
     * as a single {@code \begin{aligned}} block (provided by the
     * backend in {@code PathReplayDto.alignedDerivationLatex}).
     */
    @Test
    void appJsRendersAlignedDerivationBlockForReplay() throws IOException {
        Path appJs = locateAppJs();
        if (appJs == null) {
            return;
        }
        String content = Files.readString(appJs);
        assertTrue(content.contains("alignedDerivationLatex"),
            "app.js must read PathReplayDto.alignedDerivationLatex");
        assertTrue(content.contains("renderAlignedDerivationBlock"),
            "app.js must expose a renderAlignedDerivationBlock() helper for the replay tab");
        assertTrue(content.contains("replay-derivation-block"),
            "app.js must emit a .replay-derivation-block wrapper so the block is styleable");
    }

    /**
     * Defensive guard against the Stage 1 stray-`}` regression: the
     * single-file IIFE (`(() => { … })();`) must contain an equal
     * number of `{` and `}` characters and must end with the IIFE
     * close marker. Treat string literals naively — the file is the
     * sole owner of the math pipeline so the structural shape matters.
     */
    @Test
    void appJsRemainsAValidIifeWithBalancedBraces() throws IOException {
        Path appJs = locateAppJs();
        if (appJs == null) {
            return;
        }
        String content = Files.readString(appJs);
        assertTrue(content.trim().endsWith("})();"),
            "app.js must remain a single IIFE ending with })();");
        long open = content.chars().filter(c -> c == '{').count();
        long close = content.chars().filter(c -> c == '}').count();
        assertTrue(open == close,
            "app.js must have balanced braces (got { = " + open + " vs } = " + close + ")");
    }

    /**
     * Stage 3 pin: replay tab uses server-side comparator-flip flag and
     * the colour-diff classes (.diff-old / .diff-new) to highlight what
     * changed between consecutive steps, with a .replay-derivation-focus
     * accent on the focused row. Also regression-guards the deleted JS
     * heuristic so the legacy ASCII-comparator check cannot sneak back
     * in.
     */
    @Test
    void appJsUsesServerComparatorFlipFlagAndDiffClasses() throws IOException {
        Path appJs = locateAppJs();
        if (appJs == null) {
            return;
        }
        String content = Files.readString(appJs);
        assertTrue(content.contains("alignedDerivationLatexWithDiff"),
            "app.js must consume PathReplayDto.alignedDerivationLatexWithDiff");
        assertTrue(content.contains("wrapDiffLatex"),
            "app.js must expose a wrapDiffLatex() helper for per-step diff highlighting");
        assertTrue(content.contains("htmlClass{"),
            "app.js must emit \\htmlClass{…} wrappers around changed spans");
        assertTrue(content.contains("replay-derivation-focus"),
            "app.js must mark the focused replay row with .replay-derivation-focus");
        assertTrue(content.contains("step.comparatorFlipped === true"),
            "Stage 3: comparator-flip notice must be driven by the server flag");
        // Regression-guard the deleted JS heuristic: the old fallback
        // looked at /(<|>)/ in step.fromExpression to decide whether
        // to show the flip notice. It is now exclusively driven by the
        // server flag.
        assertFalse(content.contains("/(<|>)/.test(String(step.fromExpression"),
            "Legacy JS comparator-flip heuristic must not be re-introduced");
    }

    @Test
    void styleCssDefinesDiffAndFlipNoticeTokens() throws IOException {
        Path styleCss = locateStyleCss();
        if (styleCss == null) {
            return;
        }
        String content = Files.readString(styleCss);
        assertTrue(content.contains(".diff-old"), "missing .diff-old rule");
        assertTrue(content.contains(".diff-new"), "missing .diff-new rule");
        assertTrue(content.contains(".replay-flip-notice"),
            "missing .replay-flip-notice rule");
        assertTrue(content.contains(".replay-derivation-focus"),
            "missing .replay-derivation-focus accent rule");
        assertTrue(content.contains("--diff-old") && content.contains("--diff-new"),
            "diff CSS custom properties must be defined for token reuse");
    }

    private static Path locateStyleCss() {
        Path[] candidates = {
            Path.of("src", "main", "resources", "web", "style.css"),
            Path.of("app", "src", "main", "resources", "web", "style.css")
        };
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Stage 4 pin: the search-graph tab installs a `graphMathOverlay`
     * module that renders each Cytoscape node's expression as a KaTeX
     * HTML overlay inside a `.graph-overlay-layer` wrapper, and
     * re-projects the overlays on `layoutstop` / `pan` / `zoom` /
     * `position` events through the central `renderMath()` pipeline.
     */
    @Test
    void appJsInstallsKatexGraphOverlay() throws IOException {
        Path appJs = locateAppJs();
        if (appJs == null) {
            return;
        }
        String content = Files.readString(appJs);
        assertTrue(content.contains("graphMathOverlay"),
            "app.js must define a graphMathOverlay module");
        assertTrue(content.contains("graph-overlay-layer"),
            "app.js must emit a .graph-overlay-layer wrapper over the canvas");
        assertTrue(content.contains("graph-node-math"),
            "app.js must emit .graph-node-math hosts per Cytoscape node");
        assertTrue(content.contains("projectNode"),
            "app.js must define a projectNode helper for overlay positioning");
        assertTrue(content.contains("layoutstop"),
            "app.js must hook the Cytoscape layoutstop event for re-projection");
        assertTrue(content.contains("data-graph-math-edges"),
            "app.js must gate edge captions behind data-graph-math-edges");
        assertTrue(content.contains("expressionLatex"),
            "app.js must read SearchGraphNodeDto.expressionLatex for the overlay");
    }

    @Test
    void styleCssDefinesGraphOverlayTransitions() throws IOException {
        Path styleCss = locateStyleCss();
        if (styleCss == null) {
            return;
        }
        String content = Files.readString(styleCss);
        assertTrue(content.contains(".graph-node-math"),
            "missing .graph-node-math rule");
        assertTrue(content.contains(".graph-overlay-layer"),
            "missing .graph-overlay-layer rule");
        assertTrue(content.contains("transition: transform"),
            ".graph-node-math must declare a transform transition for smooth motion");
        assertTrue(content.contains(".graph-node-math.is-best")
                && content.contains(".graph-node-math.is-dead-end"),
            "missing best/dead-end color tokens that mirror the cytoscape block");
    }

    /**
     * Stage 5 pin: the front-end exposes a {@code renderMathLayout}
     * helper that prefers the structured layout when available, sets
     * the host's {@code aria-label} from the layout's aria field, and
     * renders aligned rows under a {@code .math-aligned-rows} grid.
     */
    @Test
    void appJsDefinesLayoutAwareRenderer() throws IOException {
        Path appJs = locateAppJs();
        if (appJs == null) {
            return;
        }
        String content = Files.readString(appJs);
        assertTrue(content.contains("renderMathLayout"),
            "app.js must define a renderMathLayout(layout, host) helper");
        assertTrue(content.contains("math-aligned-rows"),
            "app.js must emit a .math-aligned-rows grid wrapper for ALIGNED layouts");
        assertTrue(content.contains("math-aligned-row"),
            "app.js must emit per-row .math-aligned-row elements for aligned layouts");
        assertTrue(content.contains("setAttribute('aria-label'"),
            "app.js must inject the layout's aria label as aria-label on the host");
    }

    @Test
    void styleCssDefinesLayoutAwareAlignedRowRules() throws IOException {
        Path styleCss = locateStyleCss();
        if (styleCss == null) {
            return;
        }
        String content = Files.readString(styleCss);
        assertTrue(content.contains(".math-aligned-rows"),
            "missing .math-aligned-rows CSS-grid wrapper rule");
        assertTrue(content.contains(".math-aligned-row"),
            "missing per-row .math-aligned-row rule");
    }
}
