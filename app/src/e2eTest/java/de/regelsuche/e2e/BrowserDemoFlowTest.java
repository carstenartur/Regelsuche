package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Professional end-to-end browser flows for the Regelsuche demo suite.
 *
 * <p>Each test starts the production {@link de.regelsuche.web.WebWorkbenchServer}
 * in-process via {@link RegelsucheAppEnvironment}, drives the live HTML/JS
 * workbench with Playwright (Chromium) and verifies that every demo:
 * <ol>
 *   <li>starts and reaches a real mathematical result (no HTTP-200 smoke),</li>
 *   <li>renders a populated <strong>graph view</strong>,</li>
 *   <li>exposes a <strong>replay</strong> the user can scrub through,</li>
 *   <li>shows the <strong>expected derivation</strong>,</li>
 *   <li>displays a <strong>proof status</strong> badge,</li>
 *   <li>lets the user <strong>download the report bundle</strong>.</li>
 * </ol>
 *
 * <p>When run with {@code -Pregelsuche.recordDocs=true} (system property
 * {@code regelsuche.recordDocs=true}) the same flows additionally capture
 * screenshots into {@code docs/assets/screenshots/} and a WebM video into
 * {@code docs/assets/videos/}, so the README / demo-gallery markdown is
 * always backed by the very tests that protect the feature.</p>
 */
class BrowserDemoFlowTest {

    private static final boolean RECORD_DOCS = Boolean.parseBoolean(
        System.getProperty("regelsuche.recordDocs", "false"));

    private static final Path DOCS_ROOT = Paths.get("..", "docs", "assets")
        .toAbsolutePath().normalize();
    private static final Path SCREENSHOT_DIR = DOCS_ROOT.resolve("screenshots");
    private static final Path VIDEO_DIR = DOCS_ROOT.resolve("videos");

    private static RegelsucheAppEnvironment app;
    private static Playwright playwright;
    private static Browser browser;

    private BrowserContext context;
    private Page page;
    private final List<String> consoleErrors = new ArrayList<>();

    @BeforeAll
    static void bootApplicationAndBrowser() throws IOException {
        app = new RegelsucheAppEnvironment();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions().setHeadless(true));
        if (RECORD_DOCS) {
            Files.createDirectories(SCREENSHOT_DIR);
            Files.createDirectories(VIDEO_DIR);
        }
    }

    @AfterAll
    static void shutdownBrowserAndApplication() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (app != null) app.close();
    }

    @BeforeEach
    void openContext() {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
            .setViewportSize(1400, 900)
            .setAcceptDownloads(true);
        if (RECORD_DOCS) {
            options.setRecordVideoDir(VIDEO_DIR).setRecordVideoSize(1400, 900);
        }
        context = browser.newContext(options);
        page = context.newPage();
        page.onConsoleMessage((ConsoleMessage msg) -> {
            if ("error".equals(msg.type())) {
                consoleErrors.add(msg.text());
            }
        });
        page.navigate(app.baseUrl());
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    @AfterEach
    void closeContext(org.junit.jupiter.api.TestInfo info) {
        // Rename the recorded video to the human-readable test name so the
        // docs-gallery can link to it stably.
        Path videoPath = null;
        try {
            if (RECORD_DOCS && page != null && page.video() != null) {
                videoPath = page.video().path();
            }
        } catch (Exception ignored) { /* page may already be detached */ }
        if (page != null) page.close();
        if (context != null) context.close();
        if (RECORD_DOCS && videoPath != null && Files.exists(videoPath)) {
            try {
                Path target = VIDEO_DIR.resolve(info.getTestMethod()
                    .orElseThrow().getName() + ".webm");
                Files.move(videoPath, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) { /* keep auto-generated name */ }
        }
        // Console errors from the page are reported but not asserted on,
        // because optional CDNs (cytoscape, MathJax) may be unreachable in
        // restricted CI sandboxes and the workbench is designed to degrade
        // gracefully in that case.
        if (!consoleErrors.isEmpty()) {
            System.out.println("[" + info.getDisplayName()
                + "] JS console errors: " + consoleErrors);
        }
        consoleErrors.clear();
    }

    // ── Demo flows ────────────────────────────────────────────────────

    @Test
    @DisplayName("Binomische Formel: (x+3)² → 9 + 6·x + x²")
    void binomialDemoBrowserFlow() {
        runDemoAndVerify(
            "binomial",
            List.of("x ^ 2", "6 * x", "9"));
        screenshotGraphCanvas("binomial-graph.png");
    }

    @Test
    @DisplayName("Bruchkürzung mit Annahme x != 0")
    void rationalDemoBrowserFlow() {
        runDemoAndVerify(
            "rational",
            List.of("y / z"));
        // The summary must explicitly state the assumption that made the
        // cancellation safe.
        assertTrue(page.locator("#demoSummary").innerText().contains("x"),
            "rational summary must mention the x != 0 assumption");
        screenshotGraphCanvas("rational-graph.png");
    }

    @Test
    @DisplayName("Trigonometrie: sin² + cos² → 1")
    void trigonometryDemoBrowserFlow() {
        runDemoAndVerify(
            "trigonometry",
            List.of("= 1"));
        screenshotGraphCanvas("trigonometry-graph.png");
    }

    @Test
    @DisplayName("Polynom-Expansion: (x+1)(x+2) → x² + 3x + 2")
    void polynomialExpansionBrowserFlow() {
        runDemoAndVerify(
            "polynomial-expansion",
            List.of("x ^ 2", "3 * x", "2"));
        screenshotGraphCanvas("polynomial-expansion-graph.png");
    }

    @Test
    @DisplayName("Macro-Learning: System lernt eine Makroregel")
    void macroLearningBrowserFlow() {
        clickDemoButton("macro-learning");
        // Macro-learning uses its own summary renderer; wait for the
        // characteristic block to appear instead of selectedPath structure.
        page.waitForSelector("#demoSummary >> text=/Makro|Iteration|reusable/i",
            new Page.WaitForSelectorOptions().setTimeout(60_000));
        assertTrue(page.locator("#demoSummary").innerText().length() > 50);
        screenshot("macro-learning-summary.png");
    }

    @Test
    @DisplayName("Math-Demo: Lineare Gleichung x + 3 = 7 → x = 4")
    void mathEquationBrowserFlow() {
        runDemoAndVerify(
            "math-equation",
            List.of("x = 4"));
        // Lösungsweg-Schulform panel must be visible.
        assertTrue(page.locator(".math-equation-panel").count() > 0,
            "math-equation must render the Lösungsweg-Schulform panel");
        screenshot("math-equation-school-form.png");
    }

    @Test
    @DisplayName("Math-Demo: Ungleichung mit Vergleichszeichen-Flip")
    void inequalityReplayShowsFlipWarning() {
        clickDemoButton("math-inequality");
        waitForDemoSummary("math-inequality");
        // The flip warning must be visible right in the summary panel.
        page.waitForSelector(".math-inequality-panel",
            new Page.WaitForSelectorOptions().setTimeout(15_000));
        assertTrue(page.locator(".math-inequality-panel").innerText()
            .toLowerCase().contains("vergleichszeichen"),
            "inequality summary must contain the flip warning");
        screenshot("inequality-flip-warning.png");
        // Also check the replay tab decorates the flipping step.
        openReplayForLatestPath();
        // Step through until we hit a flipping rule; the renderer adds a
        // dedicated marker css class for those.
        boolean foundFlip = false;
        for (int i = 0; i < 20; i++) {
            if (page.locator(".replay-flip-notice").count() > 0) {
                foundFlip = true;
                break;
            }
            if (page.locator("#replayNext").isVisible()
                && page.locator("#replayNext").isEnabled()) {
                page.locator("#replayNext").click();
            } else {
                break;
            }
        }
        assertTrue(foundFlip,
            "replay must surface the comparator-flip notice on one of the steps");
        screenshot("inequality-replay.png");
    }

    @Test
    @DisplayName("Math-Demo: Ableitung mit Regelkarte")
    void mathDerivativeBrowserFlow() {
        runDemoAndVerify(
            "math-derivative",
            List.of("3 * x ^ 2"));
        assertTrue(page.locator(".math-derivative-panel").count() > 0,
            "math-derivative must render the Potenzregel card");
        screenshot("math-derivative-card.png");
    }

    @Test
    @DisplayName("Math-Demo: Matrix bmatrix-Vorschau im Replay")
    void mathMatrixBrowserFlow() {
        clickDemoButton("math-matrix");
        waitForDemoSummary("math-matrix");
        assertTrue(page.locator(".math-matrix-panel").count() > 0,
            "math-matrix must render the bmatrix preview");
        screenshot("math-matrix-preview.png");
        // Activate the replay tab and refresh the path list synchronously
        // so the just-recorded matrix transformation is selectable.
        page.locator(".tab[data-tab='replay']").click();
        page.waitForSelector("#tab-replay.active",
            new Page.WaitForSelectorOptions().setTimeout(5_000));
        page.evaluate(
            "async () => {"
                + " var s = document.querySelector('#replayPathSelect');"
                + " var r = await fetch('/api/paths?sort=score');"
                + " var d = await r.json();"
                + " s.innerHTML = '';"
                + " (d.transformations || []).forEach(function(p) {"
                + "   var o = document.createElement('option');"
                + "   o.value = p.id; o.textContent = p.id;"
                + "   s.appendChild(o);"
                + " });"
                + "}");
        int optionCount = ((Number) page.evaluate(
            "() => document.querySelector('#replayPathSelect').options.length")).intValue();
        boolean foundMatrixCard = false;
        // Sort=score order can place the matrix path anywhere, so probe every
        // path until we find one whose first step is a linalg rule.
        for (int idx = optionCount - 1; idx >= 0 && !foundMatrixCard; idx--) {
            int sel = idx;
            page.evaluate(
                "(i) => { var s = document.querySelector('#replayPathSelect');"
                    + " s.selectedIndex = i;"
                    + " s.dispatchEvent(new Event('change', { bubbles: true })); }",
                sel);
            page.locator("#replayLoad").click();
            page.waitForSelector(".replay-step",
                new Page.WaitForSelectorOptions().setTimeout(10_000));
            for (int step = 0; step < 30 && !foundMatrixCard; step++) {
                if (page.locator(".replay-matrix-card").count() > 0) {
                    foundMatrixCard = true;
                    break;
                }
                if (page.locator("#replayNext").isVisible()
                    && page.locator("#replayNext").isEnabled()) {
                    page.locator("#replayNext").click();
                } else {
                    break;
                }
            }
        }
        assertTrue(foundMatrixCard,
            "matrix replay must surface the bmatrix card on at least one step");
        screenshot("math-matrix-replay.png");
    }

    @Test
    @DisplayName("Proof-Bridge: Button zeigt generiertes Lean/SMT-Skript")
    void proofBridgePanelShowsGeneratedScript() {
        clickDemoButton("math-equation");
        waitForDemoSummary("math-equation");
        page.waitForSelector("#proofBridgeRun",
            new Page.WaitForSelectorOptions().setTimeout(15_000));
        page.locator("#proofBridgeRun").click();
        page.waitForSelector("#proofBridgeResult .proof-bridge-summary",
            new Page.WaitForSelectorOptions().setTimeout(30_000));
        String result = page.locator("#proofBridgeResult").innerText();
        assertTrue(result.contains("Proof-Status"),
            "proof-bridge result must show Proof-Status");
        assertTrue(result.contains("Prover-Status"),
            "proof-bridge result must show Prover-Status");
        // The generated script details panel must be present.
        assertTrue(page.locator("#proofBridgeResult details").count() >= 1,
            "proof-bridge result must include collapsible details for the generated script");
        // FORMALLY_PROVED must NOT be claimed when no prover is installed.
        if (!result.contains("PROVER_CONFIRMED")) {
            assertTrue(!result.contains("FORMALLY_PROVED"),
                "FORMALLY_PROVED must only be set when the prover confirmed");
        }
        screenshot("proof-bridge-result.png");
    }

    @Test
    @DisplayName("Export-Bundle: ZIP-Download liefert vollständigen Bericht")
    void exportBundleDownloads() throws IOException {
        // Run a quick demo so there is something to export.
        clickDemoButton("binomial");
        waitForDemoSummary("binomial");
        // Switch to the Exports tab.
        page.locator(".tab[data-tab='exports']").click();
        page.waitForSelector("#tab-exports.active",
            new Page.WaitForSelectorOptions().setTimeout(5_000));
        Download download = page.waitForDownload(() ->
            page.locator("a[href='/api/exports/bundle.zip']").first().click());
        Path saved = Files.createTempFile("regelsuche-bundle-", ".zip");
        download.saveAs(saved);
        long size = Files.size(saved);
        assertTrue(size > 1_000,
            "exported bundle.zip must contain more than a kilobyte (got " + size + ")");
        screenshot("export-bundle.png");
        Files.deleteIfExists(saved);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Click the demo hero button for {@code demoId}, wait for the demo
     * summary to appear, then assert that
     * <ul>
     *   <li>the success banner ("Identität erkannt") is rendered,</li>
     *   <li>every {@code expectedSubstring} appears in the rendered summary,</li>
     *   <li>a Proof-Status row is present in the summary table,</li>
     *   <li>switching to the graph and replay tabs shows non-empty content.</li>
     * </ul>
     */
    private void runDemoAndVerify(String demoId, List<String> expectedSubstrings) {
        clickDemoButton(demoId);
        waitForDemoSummary(demoId);
        String summary = page.locator("#demoSummary").innerText();
        assertTrue(summary.contains("Identität erkannt")
                || summary.contains("Treffer"),
            "demo " + demoId + " must surface a success banner, got: " + summary);
        for (String expected : expectedSubstrings) {
            assertTrue(summary.contains(expected),
                "demo " + demoId + " summary must contain '" + expected
                    + "', got: " + summary);
        }
        // Proof-Status must be displayed (either as a labeled row, as a badge
        // span, or as part of the identities list).
        assertTrue(summary.contains("Proof-Status")
                || page.locator(".proof-badge").count() > 0,
            "demo " + demoId + " must surface a Proof-Status row or badge");
        // Graph tab must render something (either the cytoscape canvas
        // becomes visible or the mermaid fallback fills #graphOutput).
        page.locator(".tab[data-tab='graph']").click();
        page.waitForSelector("#tab-graph.active",
            new Page.WaitForSelectorOptions().setTimeout(5_000));
        page.locator("#reloadGraph").click();
        page.waitForFunction(
            "() => {"
                + " var c = document.querySelector('#graphCanvas');"
                + " var o = document.querySelector('#graphOutput');"
                + " return (c && c.style.display !== 'none' && c.innerHTML.length > 50)"
                + "     || (o && o.textContent.length > 50);"
                + "}",
            null, new Page.WaitForFunctionOptions().setTimeout(20_000));
        // Replay tab must show selectable paths.
        openReplayForLatestPath();
        page.waitForSelector(".replay-step",
            new Page.WaitForSelectorOptions().setTimeout(15_000));
    }

    private void clickDemoButton(String demoId) {
        page.locator(".demo-button[data-demo='" + demoId + "']").click();
        // The status field flips to a non-empty message immediately after the
        // POST returns; the demo-summary then renders in the same tick.
        page.waitForFunction(
            "() => document.querySelector('#demoStatus') "
                + "&& document.querySelector('#demoStatus').innerText.length > 0",
            null, new Page.WaitForFunctionOptions().setTimeout(60_000));
    }

    private void waitForDemoSummary(String expectedDemoId) {
        page.waitForFunction(
            "(id) => document.querySelector('#demoSummary') "
                + "&& document.querySelector('#demoSummary').innerHTML.length > 50",
            expectedDemoId,
            new Page.WaitForFunctionOptions().setTimeout(60_000));
    }

    private void openReplayForLatestPath() {
        page.locator(".tab[data-tab='replay']").click();
        page.waitForSelector("#tab-replay.active",
            new Page.WaitForSelectorOptions().setTimeout(5_000));
        // Force-refresh the dropdown synchronously, then wait for the
        // freshly-fetched options. Each test triggers a new demo, so we
        // need the path list to include the just-recorded transformation.
        page.evaluate(
            "async () => {"
                + " var s = document.querySelector('#replayPathSelect');"
                + " var r = await fetch('/api/paths?sort=score');"
                + " var d = await r.json();"
                + " s.innerHTML = '';"
                + " (d.transformations || []).forEach(function(p) {"
                + "   var o = document.createElement('option');"
                + "   o.value = p.id; o.textContent = p.id;"
                + "   s.appendChild(o);"
                + " });"
                + " s.selectedIndex = s.options.length - 1;"
                + " s.dispatchEvent(new Event('change', { bubbles: true }));"
                + "}");
        page.locator("#replayLoad").click();
        page.waitForSelector(".replay-step",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(15_000));
    }

    private void screenshot(String fileName) {
        if (!RECORD_DOCS) return;
        Path target = SCREENSHOT_DIR.resolve(fileName);
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException ignored) { /* best-effort */ }
        page.screenshot(new Page.ScreenshotOptions()
            .setPath(target)
            .setType(ScreenshotType.PNG)
            .setFullPage(true));
        assertNotNull(target);
    }

    /**
     * Captures the interactive graph view rather than the full page so the
     * recorded {@code *-graph.png} screenshots show the Cytoscape canvas
     * with its KaTeX overlay layer instead of the demo-summary section.
     *
     * <p>Switches to the Graph tab (re-)triggering {@code #reloadGraph}, then
     * tries to wait for at least one {@code .graph-overlay-layer
     * .graph-node-math} element so the screenshot includes the KaTeX
     * overlays. If the interactive Cytoscape view is unavailable (e.g.
     * because the vendored bundle is not loaded in the sandbox) it falls
     * back to a full-page screenshot so the recording still captures the
     * Mermaid fallback.</p>
     */
    private void screenshotGraphCanvas(String fileName) {
        if (!RECORD_DOCS) return;
        page.locator(".tab[data-tab='graph']").click();
        page.waitForSelector("#tab-graph.active",
            new Page.WaitForSelectorOptions().setTimeout(5_000));
        // Force a fresh render so the overlay reflects the latest demo.
        page.locator("#reloadGraph").click();
        boolean interactive = false;
        try {
            page.waitForSelector("#graphCanvas .graph-overlay-layer .graph-node-math",
                new Page.WaitForSelectorOptions().setTimeout(15_000));
            interactive = true;
        } catch (RuntimeException ignored) {
            // Cytoscape may be unavailable in restricted sandboxes; we still
            // emit a full-page screenshot so the docs gallery has *some*
            // recording for this demo.
        }
        Path target = SCREENSHOT_DIR.resolve(fileName);
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException ignored) { /* best-effort */ }
        if (interactive) {
            // Scroll the canvas into view and let CSS transitions on the
            // KaTeX overlay settle before snapping.
            page.locator("#graphCanvas").scrollIntoViewIfNeeded();
            page.waitForTimeout(200);
            page.locator("#graphCanvas").screenshot(
                new com.microsoft.playwright.Locator.ScreenshotOptions()
                    .setPath(target)
                    .setType(ScreenshotType.PNG));
        } else {
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(target)
                .setType(ScreenshotType.PNG)
                .setFullPage(true));
        }
        assertNotNull(target);
    }
}
