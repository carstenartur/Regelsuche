package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * href="https://katex.org/">KaTeX</a> as the default typesetter. Previously the UI emitted
 * {@code <span class="latex">$...$</span>} placeholders that nothing ever
 * typeset; this test prevents that regression from sneaking back in.</p>
 */
class WebUiMathPipelineTest {

    private static Path locateIndexHtml() {
        Path[] candidates = {
            Path.of("src", "main", "resources", "web", "index.html"),
            Path.of("app", "src", "main", "resources", "web", "index.html")
        };
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) {
                return c;
            }
        }
        return null;
    }

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
    void indexHtmlLoadsKatexStaticallyAndAppJsDefinesRenderMath() throws IOException {
        Path indexHtml = locateIndexHtml();
        Path appJs = locateAppJs();
        assertNotNull(indexHtml, "index.html not found in expected locations");
        assertNotNull(appJs, "app.js not found in expected locations");
        String html = Files.readString(indexHtml);
        String content = Files.readString(appJs);
        assertTrue(html.contains("vendor/katex/katex.min.css"),
            "index.html must statically include KaTeX CSS");
        assertTrue(html.contains("vendor/katex/katex.min.js"),
            "index.html must statically include KaTeX JS");
        assertTrue(html.contains("vendor/katex/contrib/auto-render.min.js"),
            "index.html must statically include the KaTeX auto-render extension");
        assertTrue(content.contains("renderMath"),
            "app.js must expose a central renderMath() helper");
        assertTrue(content.contains("renderMath(document.body)"),
            "app.js must render math on DOMContentLoaded once static KaTeX is ready");
        assertFalse(html.contains("cdn.jsdelivr.net"),
            "index.html must not reference jsDelivr anymore");
        assertFalse(content.contains("unpkg.com"),
            "app.js must not reference unpkg-hosted assets anymore");
    }

    @Test
    void appJsNoLongerEmitsLegacyLatexPlaceholders() throws IOException {
        Path appJs = locateAppJs();
        assertNotNull(appJs, "app.js not found in expected locations");
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

    @Test
    void webAssetsDoNotReferenceMathJaxAnymore() throws IOException {
        Path indexHtml = locateIndexHtml();
        Path appJs = locateAppJs();
        assertNotNull(indexHtml, "index.html not found in expected locations");
        assertNotNull(appJs, "app.js not found in expected locations");
        assertFalse(Files.readString(indexHtml).toLowerCase().contains("mathjax"),
            "index.html must not reference MathJax anymore");
        assertFalse(Files.readString(appJs).toLowerCase().contains("mathjax"),
            "app.js must not reference MathJax anymore");
    }

    /**
     * Stage 2 pin: the replay panel must render the whole derivation
     * as a single {@code \begin{aligned}} block (provided by the
     * backend in {@code PathReplayDto.alignedDerivationLatex}).
     */
    @Test
    void appJsRendersAlignedDerivationBlockForReplay() throws IOException {
        Path appJs = locateAppJs();
        assertNotNull(appJs, "app.js not found in expected locations");
        String content = Files.readString(appJs);
        assertTrue(content.contains("alignedDerivationLatex"),
            "app.js must read PathReplayDto.alignedDerivationLatex");
        assertTrue(content.contains("derivationLayout"),
            "app.js must prefer PathReplayDto.derivationLayout when rendering the replay block");
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
        assertNotNull(appJs, "app.js not found in expected locations");
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
        assertNotNull(appJs, "app.js not found in expected locations");
        String content = Files.readString(appJs);
        assertTrue(content.contains("renderMathLayout(replayState.derivationLayout, derivationHost)"),
            "app.js must render the replay block via renderMathLayout()");
        assertTrue(content.contains("renderMathLayout(step.layout, toHost)"),
            "app.js must render replay steps via the structured step layout");
        assertTrue(content.contains("trust: false"),
            "KaTeX trust mode must be disabled once layout rendering is primary");
        assertTrue(content.contains("replay-derivation-focus"),
            "app.js must mark the focused replay row with .replay-derivation-focus");
        assertTrue(content.contains("step.comparatorFlipped === true"),
            "Stage 3: comparator-flip notice must be driven by the server flag");
        assertFalse(content.contains("wrapDiffLatex("),
            "Legacy string-based diff wrapping must not remain in app.js");
        assertFalse(content.contains("htmlClass{"),
            "app.js must not emit \\htmlClass{…} wrappers anymore");
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
        assertNotNull(styleCss, "style.css not found in expected locations");
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
        assertNotNull(appJs, "app.js not found in expected locations");
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
        assertTrue(content.contains("payload.layout"),
            "graph overlays must prefer SearchGraphNodeDto/SearchGraphEdgeDto.layout when present");
    }

    @Test
    void styleCssDefinesGraphOverlayTransitions() throws IOException {
        Path styleCss = locateStyleCss();
        assertNotNull(styleCss, "style.css not found in expected locations");
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
        assertNotNull(appJs, "app.js not found in expected locations");
        String content = Files.readString(appJs);
        assertTrue(content.contains("renderMathLayout"),
            "app.js must define a renderMathLayout(layout, host) helper");
        assertTrue(content.contains("step.layout"),
            "replay step rendering must pass ReplayStep.layout to renderMathLayout()");
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
        assertNotNull(styleCss, "style.css not found in expected locations");
        String content = Files.readString(styleCss);
        assertTrue(content.contains(".math-aligned-rows"),
            "missing .math-aligned-rows CSS-grid wrapper rule");
        assertTrue(content.contains(".math-aligned-row"),
            "missing per-row .math-aligned-row rule");
    }
}
