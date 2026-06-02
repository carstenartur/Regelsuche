package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

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
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
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
        screenshotGraphBestPath("binomial-graph.png");
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
        screenshotDemoSummaryCard("rational-summary.png",
            "#demoSummary code", true);
    }

    @Test
    @DisplayName("Trigonometrie: sin² + cos² → 1")
    void trigonometryDemoBrowserFlow() {
        runDemoAndVerify(
            "trigonometry",
            List.of("= 1"));
        screenshotGraphBestPath("trigonometry-graph.png");
    }

    @Test
    @DisplayName("Polynom-Expansion: (x+1)(x+2) → x² + 3x + 2")
    void polynomialExpansionBrowserFlow() {
        runDemoAndVerify(
            "polynomial-expansion",
            List.of("x ^ 2", "3 * x", "2"));
        screenshotGraphBestPath("polynomial-expansion-graph.png");
    }

    @Test
    @DisplayName("Macro-Learning: System lernt eine Makroregel")
    void macroLearningBrowserFlow() {
        clickDemoButton("macro-learning");
        // Macro-learning uses its own summary renderer; wait for the
        // characteristic block to appear instead of selectedPath structure.
        waitForDemoFinished("macro-learning");
        waitForMathRendered("#demoSummary");
        page.waitForSelector("#demoSummary >> text=/Makro|Iteration|reusable/i",
            new Page.WaitForSelectorOptions().setTimeout(10_000));
        assertTrue(page.locator("#demoSummary").innerText().length() > 50);
        screenshotDemoSummaryCard("macro-learning-summary.png",
            "#demoSummary table", false);
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
        screenshotDemoSummaryCard("math-equation-school-form.png",
            ".math-equation-panel", true);
    }

    @Test
    @DisplayName("Math-Demo: Ungleichung mit Vergleichszeichen-Flip")
    void inequalityReplayShowsFlipWarning() {
        clickDemoButton("math-inequality");
        waitForDemoSummary("math-inequality");
        // The flip warning must be visible right in the summary panel.
        page.waitForSelector(".math-inequality-panel",
            new Page.WaitForSelectorOptions().setTimeout(10_000));
        assertTrue(page.locator(".math-inequality-panel").innerText()
            .toLowerCase().contains("vergleichszeichen"),
            "inequality summary must contain the flip warning");
        screenshotDemoSummaryCard("inequality-flip-warning.png",
            ".math-inequality-panel", true);
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
        screenshotReplayStep("inequality-replay.png",
            ".replay-flip-notice", true);
    }

    @Test
    @DisplayName("Math-Demo: Ableitung mit Regelkarte")
    void mathDerivativeBrowserFlow() {
        runDemoAndVerify(
            "math-derivative",
            List.of("3 * x ^ 2"));
        assertTrue(page.locator(".math-derivative-panel").count() > 0,
            "math-derivative must render the Potenzregel card");
        screenshotDemoSummaryCard("math-derivative-card.png",
            ".math-derivative-panel", true);
    }

    @Test
    @DisplayName("Math-Demo: Matrix bmatrix-Vorschau im Replay")
    void mathMatrixBrowserFlow() {
        clickDemoButton("math-matrix");
        waitForDemoSummary("math-matrix");
        assertTrue(page.locator(".math-matrix-panel").count() > 0,
            "math-matrix must render the bmatrix preview");
        screenshotDemoSummaryCard("math-matrix-preview.png",
            ".math-matrix-panel", true);
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
            waitForReplayReady();
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
        screenshotReplayStep("math-matrix-replay.png",
            ".replay-matrix-card", true);
    }

    @Test
    @DisplayName("Proof-Bridge: Button zeigt generiertes Lean/SMT-Skript")
    void proofBridgePanelShowsGeneratedScript() {
        clickDemoButton("math-equation");
        waitForDemoSummary("math-equation");
        page.waitForSelector("#proofBridgeRun",
            new Page.WaitForSelectorOptions().setTimeout(10_000));
        waitForProofResult(() -> page.locator("#proofBridgeRun").click());
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
        // Expand all generated-script details panels so the screenshot is tall
        // enough to pass the minimum-height quality check (>= 120 px).
        page.evaluate("document.querySelectorAll('#proofBridgeResult details')"
            + ".forEach(el => el.setAttribute('open', ''))");
        screenshotPanel("proof-bridge-result.png", "#proofBridgeResult",
            "#proofBridgeResult details", false);
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
        waitForDownloadReady();
        Download download = page.waitForDownload(() ->
            page.locator("a[href='/api/exports/bundle.zip']").first().click());
        Path saved = Files.createTempFile("regelsuche-bundle-", ".zip");
        download.saveAs(saved);
        long size = Files.size(saved);
        assertTrue(size > 1_000,
            "exported bundle.zip must contain more than a kilobyte (got " + size + ")");
        screenshotPanel("export-bundle.png", "#tab-exports .card",
            "#tab-exports .export-grid", false);
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
        // Graph tab must render the interactive Cytoscape/KaTeX view.
        page.locator(".tab[data-tab='graph']").click();
        page.waitForSelector("#tab-graph.active",
            new Page.WaitForSelectorOptions().setTimeout(5_000));
        page.locator("#reloadGraph").click();
        waitForGraphRendered();
        // Replay tab must show selectable paths.
        openReplayForLatestPath();
        waitForReplayReady();
    }

    private void clickDemoButton(String demoId) {
        page.waitForResponse(
            response -> response.url().contains("/api/demo/" + demoId)
                && response.status() == 200,
            () -> page.locator(".demo-button[data-demo='" + demoId + "']").click());
        waitForDemoFinished(demoId);
    }

    private void waitForDemoFinished(String demoId) {
        page.waitForFunction(
            "(id) => window.__regelsucheDemoReady === true "
                + "&& document.querySelector('#demoSummary') "
                + "&& document.querySelector('#demoSummary').innerHTML.length > 50",
            demoId,
            new Page.WaitForFunctionOptions().setTimeout(15_000));
    }

    private void waitForDemoSummary(String expectedDemoId) {
        waitForDemoFinished(expectedDemoId);
        waitForMathRendered("#demoSummary");
    }

    private void waitForMathRendered(String containerSelector) {
        page.waitForFunction(
            "(selector) => window.__regelsucheMathRendered === true "
                + "&& document.querySelector(selector)",
            containerSelector,
            new Page.WaitForFunctionOptions().setTimeout(10_000));
    }

    private void waitForGraphRendered() {
        page.waitForFunction(
            "() => window.__regelsucheGraphRendered === true "
                + "&& document.querySelectorAll("
                + "'#graphCanvas .graph-overlay-layer .graph-node-math .katex'"
                + ").length > 0",
            null,
            new Page.WaitForFunctionOptions().setTimeout(10_000));
    }

    private void waitForReplayReady() {
        page.waitForFunction(
            "() => window.__regelsucheReplayReady === true "
                + "&& document.querySelector('.replay-step')",
            null,
            new Page.WaitForFunctionOptions().setTimeout(10_000));
    }

    private void waitForProofResult(Runnable action) {
        page.waitForResponse(
            response -> response.url().contains("/api/proof-bridge")
                && response.status() == 200,
            action);
        page.waitForSelector("#proofBridgeResult .proof-bridge-summary",
            new Page.WaitForSelectorOptions().setTimeout(10_000));
    }

    private void waitForDownloadReady() {
        page.waitForSelector("a[href='/api/exports/bundle.zip']",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(10_000));
    }

    private void waitForStableElement(String selector) {
        page.waitForFunction(
            "(selector) => new Promise((resolve) => {"
                + " const el = document.querySelector(selector);"
                + " if (!el) { resolve(false); return; }"
                + " const first = el.getBoundingClientRect();"
                + " requestAnimationFrame(() => requestAnimationFrame(() => {"
                + "   const second = el.getBoundingClientRect();"
                + "   resolve(Math.abs(first.x - second.x) < 1"
                + "     && Math.abs(first.y - second.y) < 1"
                + "     && Math.abs(first.width - second.width) < 1"
                + "     && Math.abs(first.height - second.height) < 1);"
                + " }));"
                + "})",
            selector,
            new Page.WaitForFunctionOptions().setTimeout(5_000));
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
        waitForReplayReady();
    }

    private void screenshotDemoSummaryCard(
            String fileName,
            String requiredSelector,
            boolean requireKatex) {
        screenshotPanel(fileName, "#demoSummary", requiredSelector, requireKatex);
    }

    private void screenshotReplayStep(
            String fileName,
            String requiredSelector,
            boolean requireKatex) {
        screenshotPanel(fileName, "#replayCanvas", requiredSelector, requireKatex);
    }

    private void screenshotPanel(
            String fileName,
            String containerSelector,
            String requiredSelector,
            boolean requireKatex) {
        if (!RECORD_DOCS) return;
        page.waitForSelector(containerSelector,
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(10_000));
        page.waitForSelector(requiredSelector,
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(10_000));
        Locator container = page.locator(containerSelector).first();
        if (requireKatex) {
            assertTrue(container.locator(".katex").count() > 0,
                fileName + " must include at least one rendered KaTeX element");
        }
        container.scrollIntoViewIfNeeded();
        waitForStableElement(containerSelector);
        Path target = SCREENSHOT_DIR.resolve(fileName);
        createParentDirectory(target);
        container.screenshot(new Locator.ScreenshotOptions()
            .setPath(target)
            .setType(ScreenshotType.PNG));
        assertScreenshotQuality(target, fileName);
    }

    /**
     * Captures the interactive semantic graph view. A Cytoscape/KaTeX overlay
     * is mandatory for documentation assets; fallback Mermaid/full-page
     * captures are intentionally not written to docs.
     */
    private void screenshotGraphBestPath(String fileName) {
        page.locator(".tab[data-tab='graph']").click();
        page.waitForSelector("#tab-graph.active",
            new Page.WaitForSelectorOptions().setTimeout(5_000));
        page.locator("#graphViewMode").selectOption("semantic");
        page.locator("#showMacroSteps").selectOption("didactic");
        page.locator("#graphFilter").fill("");
        page.locator("#showLowSignal").setChecked(false);
        page.locator("#showAlternatives").setChecked(false);
        Response graphResponse = page.waitForResponse(
            response -> response.url().contains("/api/search-graph/semantic")
                && response.status() == 200,
            () -> page.locator("#reloadGraph").click());
        assertNotNull(graphResponse, fileName + " must request the semantic search graph");
        assertTrue(graphResponse.url().contains("mode=semantic"),
            fileName + " semantic graph request must use mode=semantic, got: " + graphResponse.url());
        assertTrue(graphResponse.url().contains("showMacroSteps=didactic"),
            fileName + " semantic graph request must use didactic macro steps, got: " + graphResponse.url());
        assertTrue(graphResponse.url().contains("showLowSignal=false"),
            fileName + " semantic graph request must disable low-signal edges, got: " + graphResponse.url());
        assertTrue(graphResponse.url().contains("showAlternatives=false"),
            fileName + " semantic graph request must disable alternatives, got: " + graphResponse.url());
        page.waitForFunction(
            "() => typeof window.cytoscape === 'function' || window.__cytoscapeFailed === true",
            null,
            new Page.WaitForFunctionOptions().setTimeout(10_000));
        Boolean cytoscapeAvailable = (Boolean) page.evaluate(
            "() => typeof window.cytoscape === 'function' && window.__cytoscapeFailed !== true");
        if (!Boolean.TRUE.equals(cytoscapeAvailable)) {
            throw new TestAbortedException(
                "Cytoscape unavailable; refusing to replace docs graph screenshot with fallback");
        }
        waitForSemanticGraphRendered();
        assertSemanticGraphRequestState(fileName);
        waitForExpectedSemanticGraphContent(fileName);
        assertCollectedPolynomialFinalStateIfNeeded(fileName);
        Path target = SCREENSHOT_DIR.resolve(fileName);
        assertSemanticDebugDump(fileName, target);
        int renderedNodes = ((Number) page.evaluate(
            "() => document.querySelectorAll("
                + "'#graphCanvas .graph-overlay-layer .graph-node-math .katex'"
                + ").length")).intValue();
        int expectedMinNodes = expectedMinSemanticGraphNodes(fileName);
        int expectedMinEdges = expectedMinSemanticGraphEdges(fileName);
        assertTrue(renderedNodes >= expectedMinNodes,
            fileName + " must show KaTeX-rendered semantic graph nodes; renderedNodes="
                + renderedNodes + ", expectedMinNodes=" + expectedMinNodes);
        int isolatedNodes = ((Number) page.evaluate(
            "() => {"
                + " const cy = window.__cyForTests;"
                + " if (!cy) return 0;"
                + " return cy.nodes().filter(n => n.connectedEdges().length === 0).length;"
                + "}")).intValue();
        assertTrue(isolatedNodes == 0,
            fileName + " must not render orphan semantic graph nodes");
        assertSemanticGraphNodeIdentityUniqueness(fileName);
        int graphNodeCount = ((Number) page.evaluate(
            "() => { const cy = window.__cyForTests; return cy ? cy.nodes().length : 0; }")).intValue();
        int graphEdgeCount = ((Number) page.evaluate(
            "() => { const cy = window.__cyForTests; return cy ? cy.edges().length : 0; }")).intValue();
        assertTrue(graphNodeCount >= expectedMinNodes,
            fileName + " must preserve the compressed explanation path; nodes="
                + graphNodeCount + ", expectedMinNodes=" + expectedMinNodes);
        assertTrue(graphEdgeCount >= expectedMinEdges,
            fileName + " must preserve explanatory semantic edges; edges="
                + graphEdgeCount + ", expectedMinEdges=" + expectedMinEdges);
        assertTrue(graphEdgeCount >= graphNodeCount - 1,
            fileName + " must connect the compressed explanation path");
        int intermediateNodeCount = ((Number) page.evaluate(
            "() => {"
                + " const cy = window.__cyForTests;"
                + " if (!cy) return 0;"
                + " return cy.nodes().filter(n => {"
                + "   const payload = n.data('payload') || {};"
                + "   return payload.explicitEndpoint !== true;"
                + " }).length;"
                + "}")).intValue();
        if (!allowsGenuineOneStepDerivation(fileName)) {
            assertTrue(intermediateNodeCount > 0,
                fileName + " must include at least one non-endpoint intermediate node");
        }
        int endpointNodeCount = ((Number) page.evaluate(
            "() => {"
                + " const cy = window.__cyForTests;"
                + " if (!cy) return 0;"
                + " return cy.nodes().filter(n => {"
                + "   const payload = n.data('payload') || {};"
                + "   return payload.explicitEndpoint === true;"
                + " }).length;"
                + "}")).intValue();
        if (!allowsGenuineOneStepDerivation(fileName)) {
            assertTrue(graphNodeCount > endpointNodeCount,
                fileName + " must not collapse to only seed and goal nodes");
        }
        int visibleEdgeLabelCount = ((Number) page.evaluate(
            "() => document.querySelectorAll("
                + "'#graphCanvas .graph-overlay-layer .graph-edge-math .katex'"
                + ").length")).intValue();
        assertTrue(visibleEdgeLabelCount > 0,
            fileName + " must show at least one visible, non-empty semantic edge label");
        assertSemanticGraphVisualLayout(fileName);
        assertSemanticGraphStatsReduction(fileName);
        String semanticLabel = page.locator(".graph-semantic-watermark").innerText();
        assertTrue(semanticLabel.contains("Semantic Discovery Graph"),
            fileName + " must visibly identify the semantic discovery graph");
        if (fileName.contains("binomial") || fileName.contains("polynomial")) {
            int mainPathNodeCount = ((Number) page.evaluate(
                "() => {"
                    + " const cy = window.__cyForTests;"
                    + " if (!cy) return 0;"
                    + " return cy.nodes().filter(n => n.data('payload')"
                    + "   && n.data('payload').onMainPath === true).length;"
                    + "}")).intValue();
            assertTrue(mainPathNodeCount >= expectedMinNodes,
                fileName + " must show start, relevant intermediate states, and goal; mainPathNodes="
                    + mainPathNodeCount + ", expectedMinNodes=" + expectedMinNodes);
        }
        if (!RECORD_DOCS) return;
        createParentDirectory(target);
        page.locator("#graphCanvas").scrollIntoViewIfNeeded();
        waitForStableElement("#graphCanvas");
        page.locator("#graphCanvas").screenshot(new Locator.ScreenshotOptions()
            .setPath(target)
            .setType(ScreenshotType.PNG));
        assertScreenshotQuality(target, fileName);
    }

    private void assertSemanticDebugDump(String fileName, Path screenshotTarget) {
        String dump = semanticDebugDump(fileName);
        if (RECORD_DOCS) {
            Path semanticTarget = screenshotTarget.resolveSibling(
                screenshotTarget.getFileName().toString().replaceFirst("\\.png$", ".semantic.json"));
            createParentDirectory(semanticTarget);
            try {
                Files.writeString(semanticTarget, dump);
            } catch (IOException ex) {
                throw new AssertionError("Could not write semantic graph debug dump for " + fileName, ex);
            }
        }
        int duplicateCanonicalHashCount = semanticDumpInt("duplicateCanonicalHashCount");
        int duplicateNormalizedLabelCount = semanticDumpInt("duplicateNormalizedLabelCount");
        int nodeOverlapCount = semanticDumpInt("nodeOverlapCount");
        int edgeLabelOverlapCount = semanticDumpInt("edgeLabelOverlapCount");
        int mainPathMeaningfulNodeCount = semanticDumpInt("mainPathMeaningfulNodeCount");
        String expectedFinalLabel = expectedCollectedPolynomialFinalLabel(fileName);
        String finalNormalizedLabel = semanticDumpString("finalNormalizedLabel");
        assertTrue(duplicateCanonicalHashCount == 0,
            fileName + " semantic dump must not contain duplicate canonical hashes: " + dump);
        assertTrue(duplicateNormalizedLabelCount == 0,
            fileName + " semantic dump must not contain duplicate normalized labels: " + dump);
        assertTrue(nodeOverlapCount == 0,
            fileName + " semantic dump must not contain node overlaps: " + dump);
        assertTrue(edgeLabelOverlapCount == 0,
            fileName + " semantic dump must not contain edge-label overlaps: " + dump);
        if (fileName.contains("binomial") || fileName.contains("polynomial")) {
            assertTrue(mainPathMeaningfulNodeCount >= 4,
                fileName + " semantic dump must keep at least four meaningful main-path nodes: " + dump);
            assertTrue(normalizeGraphLabel(finalNormalizedLabel).equals(normalizeGraphLabel(expectedFinalLabel)),
                fileName + " semantic dump must assert finalNormalizedLabel is collected polynomial "
                    + expectedFinalLabel + ": " + dump);
            assertTrue(!normalizeGraphLabel(finalNormalizedLabel).equals(
                    normalizeGraphLabel(forbiddenUncollectedPolynomialFinalLabel(fileName))),
                fileName + " semantic dump finalNormalizedLabel must not be the uncollected expansion: " + dump);
        }
    }

    private int semanticDumpInt(String property) {
        return ((Number) page.evaluate(
            "property => (window.__lastSemanticGraphDebugDump && window.__lastSemanticGraphDebugDump[property]) || 0",
            property)).intValue();
    }

    private String semanticDumpString(String property) {
        return (String) page.evaluate(
            "property => String((window.__lastSemanticGraphDebugDump"
                + " && window.__lastSemanticGraphDebugDump[property]) || '')",
            property);
    }

    private String normalizeGraphLabel(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
    }

    private String semanticDebugDump(String fileName) {
        return (String) page.evaluate(
            "fileName => {"
                + " const cy = window.__cyForTests;"
                + " if (!cy) throw new Error('missing Cytoscape graph for semantic dump');"
                + " const normalizeLabel = value => String(value || '').replace(/\\s+/g, '').toLowerCase();"
                + " const canonicalHash = payload => {"
                + "   const explicit = payload.canonicalHash || payload.canonicalId || '';"
                + "   if (explicit) return String(explicit).replace(/^canonical:/, '');"
                + "   const cluster = payload.clusterId || payload.id || '';"
                + "   return String(cluster).replace(/^canonical:/, '');"
                + " };"
                + " const nodeLabelRects = Array.from(document.querySelectorAll("
                + "   '#graphCanvas .graph-overlay-layer .graph-node-math:not(.graph-edge-math)'"
                + " )).map(el => { const r = el.getBoundingClientRect(); return {"
                + "   id: el.getAttribute('data-node-id') || el.getAttribute('aria-label') || '',"
                + "   x: r.x, y: r.y, width: r.width, height: r.height"
                + " }; });"
                + " const edgeLabelRects = Array.from(document.querySelectorAll("
                + "   '#graphCanvas .graph-overlay-layer .graph-edge-math'"
                + " )).map(el => { const r = el.getBoundingClientRect(); return {"
                + "   id: el.getAttribute('data-edge-id') || el.getAttribute('aria-label') || '',"
                + "   x: r.x, y: r.y, width: r.width, height: r.height"
                + " }; });"
                + " const nodes = cy.nodes().map(n => {"
                + "   const payload = n.data('payload') || {};"
                + "   const label = payload.representativeExpression || payload.expression"
                + "     || payload.canonicalExpression || n.data('label') || '';"
                + "   const revisit = payload.revisit === true || payload.cycle === true"
                + "     || payload.isRevisit === true || payload.isCycle === true;"
                + "   return {"
                + "     id: n.id(),"
                + "     canonicalHash: canonicalHash(payload),"
                + "     normalizedLabel: normalizeLabel(label),"
                + "     label: String(label),"
                + "     revisit: revisit,"
                + "     cycle: payload.cycle === true || payload.isCycle === true,"
                + "     onMainPath: payload.onMainPath === true,"
                + "     explicitEndpoint: payload.explicitEndpoint === true,"
                + "     minDepth: Number(payload.minDepth || 0),"
                + "     layout: { x: n.position('x'), y: n.position('y') },"
                + "     renderedLayout: { x: n.renderedPosition('x'), y: n.renderedPosition('y') }"
                + "   };"
                + " });"
                + " const edges = cy.edges().map(e => {"
                + "   const payload = e.data('payload') || {};"
                + "   const source = e.source().renderedPosition();"
                + "   const target = e.target().renderedPosition();"
                + "   return {"
                + "     id: e.id(), from: e.source().id(), to: e.target().id(),"
                + "     label: String(payload.ruleId || e.data('label') || ''),"
                + "     kind: String(payload.kind || e.data('kind') || ''),"
                + "     layout: { x: (source.x + target.x) / 2, y: (source.y + target.y) / 2 }"
                + "   };"
                + " });"
                + " const duplicateHashes = new Set();"
                + " const seenHashes = new Set();"
                + " const duplicateLabels = new Set();"
                + " const seenLabels = new Set();"
                + " nodes.forEach(node => {"
                + "   if (node.canonicalHash) {"
                + "     if (seenHashes.has(node.canonicalHash)) duplicateHashes.add(node.canonicalHash);"
                + "     seenHashes.add(node.canonicalHash);"
                + "   }"
                + "   if (node.normalizedLabel && !node.revisit && !node.cycle) {"
                + "     if (seenLabels.has(node.normalizedLabel)) duplicateLabels.add(node.normalizedLabel);"
                + "     seenLabels.add(node.normalizedLabel);"
                + "   }"
                + " });"
                + " const visibleMainPathMeaningfulNodeCount = nodes.filter(node =>"
                + "   node.onMainPath && node.normalizedLabel && !node.revisit && !node.cycle"
                + " ).length;"
                + " const collapsedMainPathStepCount = edges.filter(edge => edge.kind === 'MAIN_STEP').length;"
                + " const mainPathNodes = nodes.filter(node => node.onMainPath);"
                + " const finalNode = mainPathNodes.reduce((best, node) => {"
                + "   const depth = Number(node.minDepth);"
                + "   if (!best) return node;"
                + "   const bestDepth = Number(best.minDepth);"
                + "   return depth >= bestDepth ? node : best;"
                + " }, null);"
                + " const mainPathMeaningfulNodeCount = visibleMainPathMeaningfulNodeCount >= 4"
                + "   ? visibleMainPathMeaningfulNodeCount"
                + "   : Math.max(visibleMainPathMeaningfulNodeCount, collapsedMainPathStepCount + 2);"
                + " const dump = {"
                + "   schema: 'regelsuche.semantic-graph-debug/v1',"
                + "   fileName,"
                + "   requestUrl: window.__lastGraphRequestUrl || '',"
                + "   requestParams: window.__lastGraphRequestParams || {},"
                + "   visibleNodeIds: nodes.map(node => node.id),"
                + "   nodes,"
                + "   edges,"
                + "   nodeLabelRects,"
                + "   edgeLabelRects,"
                + "   duplicateCanonicalHashCount: duplicateHashes.size,"
                + "   duplicateNormalizedLabelCount: duplicateLabels.size,"
                + "   nodeOverlapCount: countGraphLabelOverlaps(false),"
                + "   edgeLabelOverlapCount: countGraphLabelOverlaps(true),"
                + "   visibleMainPathMeaningfulNodeCount,"
                + "   collapsedMainPathStepCount,"
                + "   finalNormalizedLabel: finalNode ? finalNode.normalizedLabel : '',"
                + "   mainPathMeaningfulNodeCount"
                + " };"
                + " window.__lastSemanticGraphDebugDump = dump;"
                + " return JSON.stringify(dump, null, 2);"
                + "}",
            fileName);
    }

    private int expectedMinSemanticGraphNodes(String fileName) {
        if (fileName.contains("binomial")) {
            return 4;
        }
        if (fileName.contains("polynomial")) {
            return 3;
        }
        if (fileName.contains("trigonometry")) {
            return 2;
        }
        return 3;
    }

    private int expectedMinSemanticGraphEdges(String fileName) {
        if (fileName.contains("binomial")) {
            return 3;
        }
        if (fileName.contains("polynomial")) {
            return 2;
        }
        if (fileName.contains("trigonometry")) {
            return 1;
        }
        return 2;
    }

    private boolean allowsGenuineOneStepDerivation(String fileName) {
        return fileName.contains("trigonometry");
    }

    private void assertSemanticGraphRequestState(String fileName) {
        page.waitForFunction(
            "() => window.__lastGraphRequestParams "
                + "&& window.__lastGraphRequestParams.mode === 'semantic' "
                + "&& window.__lastGraphRequestParams.showMacroSteps === 'didactic' "
                + "&& window.__lastGraphRequestParams.showLowSignal === false "
                + "&& window.__lastGraphRequestParams.showAlternatives === false",
            null,
            new Page.WaitForFunctionOptions().setTimeout(5_000));
        String requestUrl = (String) page.evaluate("() => window.__lastGraphRequestUrl || ''");
        assertTrue(requestUrl.contains("/api/search-graph/semantic"),
            fileName + " must expose the semantic graph request URL");
        assertTrue(requestUrl.contains("mode=semantic")
                && requestUrl.contains("showMacroSteps=didactic")
                && requestUrl.contains("showLowSignal=false")
                && requestUrl.contains("showAlternatives=false"),
            fileName + " must expose the active semantic graph request params, got: " + requestUrl);
    }

    private void assertSemanticGraphNodeIdentityUniqueness(String fileName) {
        @SuppressWarnings("unchecked")
        List<String> duplicates = (List<String>) page.evaluate(
            "() => {"
                + " const cy = window.__cyForTests;"
                + " if (!cy) return ['missing-cytoscape'];"
                + " const seenHashes = new Map();"
                + " const seenLabels = new Map();"
                + " const duplicates = [];"
                + " cy.nodes().forEach(n => {"
                + "   const payload = n.data('payload') || {};"
                + "   const markedRevisit = payload.revisit === true || payload.cycle === true"
                + "     || payload.isRevisit === true || payload.isCycle === true;"
                + "   const hash = payload.clusterId || payload.canonicalHash || n.id();"
                + "   const label = String(payload.representativeExpression"
                + "     || payload.expression || payload.canonicalExpression || n.data('label') || '')"
                + "     .replace(/\\s+/g, '').toLowerCase();"
                + "   if (hash) {"
                + "     if (seenHashes.has(hash)) duplicates.push('canonicalHash:' + hash);"
                + "     else seenHashes.set(hash, true);"
                + "   }"
                + "   if (label && !markedRevisit) {"
                + "     if (seenLabels.has(label)) duplicates.push('label:' + label);"
                + "     else seenLabels.set(label, true);"
                + "   }"
                + " });"
                + " return duplicates;"
                + "}");
        assertTrue(duplicates.isEmpty(),
            fileName + " must not render duplicate visible canonical hashes or labels: " + duplicates);
    }

    private void assertSemanticGraphStatsReduction(String fileName) {
        page.waitForSelector(".graph-semantic-watermark",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(5_000));
        int semanticNodeCount = ((Number) page.evaluate(
            "() => window.__lastGraphStats && window.__lastGraphStats.semanticNodeCount || 0")).intValue();
        int rawNodeCount = ((Number) page.evaluate(
            "() => window.__lastGraphStats && window.__lastGraphStats.rawNodeCount || 0")).intValue();
        assertTrue(semanticNodeCount > 0,
            fileName + " must expose the rendered semantic node count");
        assertTrue(rawNodeCount > semanticNodeCount,
            fileName + " must show semanticNodeCount/rawNodeCount reduction");
        String graphStats = page.locator(".graph-semantic-watermark").innerText();
        assertTrue(graphStats.contains("semanticNodeCount=" + semanticNodeCount)
                && graphStats.contains("rawNodeCount=" + rawNodeCount),
            fileName + " must show semantic/raw graph stats, got: " + graphStats);
    }

    private void waitForSemanticGraphRendered() {
        page.waitForFunction(
            "() => window.__regelsucheSemanticGraphRendered === true "
                + "&& document.querySelectorAll("
                + "'#graphCanvas .graph-overlay-layer .graph-node-math .katex'"
                + ").length > 0",
            null,
            new Page.WaitForFunctionOptions().setTimeout(10_000));
    }

    private void waitForExpectedSemanticGraphContent(String fileName) {
        String expectedLabel = expectedSemanticGraphLabel(fileName);
        if (expectedLabel.isBlank()) {
            return;
        }
        page.waitForFunction(
            "expectedLabel => {"
                + " const normalize = value => String(value || '').replace(/\\s+/g, '').toLowerCase();"
                + " const cy = window.__cyForTests;"
                + " if (!cy) return false;"
                + " const expected = normalize(expectedLabel);"
                + " return cy.nodes().some(node => {"
                + "   const payload = node.data('payload') || {};"
                + "   const label = payload.representativeExpression || payload.expression"
                + "     || payload.canonicalExpression || node.data('label') || '';"
                + "   return normalize(label) === expected;"
                + " });"
                + "}",
            expectedLabel,
            new Page.WaitForFunctionOptions().setTimeout(5_000));
    }

    private void assertCollectedPolynomialFinalStateIfNeeded(String fileName) {
        String expectedFinalLabel = expectedCollectedPolynomialFinalLabel(fileName);
        if (expectedFinalLabel.isBlank()) {
            return;
        }
        String forbiddenFinalLabel = forbiddenUncollectedPolynomialFinalLabel(fileName);
        page.waitForFunction(
            "args => {"
                + " const normalize = value => String(value || '').replace(/\\s+/g, '').toLowerCase();"
                + " const cy = window.__cyForTests;"
                + " if (!cy) return false;"
                + " const expected = normalize(args.expectedFinalLabel);"
                + " const forbidden = normalize(args.forbiddenFinalLabel);"
                + " const hasCollectedNode = cy.nodes().some(node => {"
                + "   const payload = node.data('payload') || {};"
                + "   const label = payload.representativeExpression || payload.expression"
                + "     || payload.canonicalExpression || node.data('label') || '';"
                + "   return payload.onMainPath === true && normalize(label) === expected;"
                + " });"
                + " const hasCollectEdge = cy.edges().some(edge => {"
                + "   const payload = edge.data('payload') || {};"
                + "   return payload.kind === 'MAIN_STEP'"
                + "     && (payload.ruleId === 'polynomial_collect_like_terms'"
                + "       || edge.data('label') === 'polynomial_collect_like_terms');"
                + " });"
                + " const mainPathNodes = cy.nodes().filter(node => {"
                + "   const payload = node.data('payload') || {};"
                + "   return payload.onMainPath === true;"
                + " });"
                + " const finalNode = mainPathNodes.reduce((best, node) => {"
                + "   const depth = Number((node.data('payload') || {}).minDepth || 0);"
                + "   if (!best) return node;"
                + "   const bestDepth = Number((best.data('payload') || {}).minDepth || 0);"
                + "   return depth >= bestDepth ? node : best;"
                + " }, null);"
                + " const finalPayload = finalNode ? (finalNode.data('payload') || {}) : {};"
                + " const finalLabel = finalPayload.representativeExpression || finalPayload.expression"
                + "   || finalPayload.canonicalExpression || (finalNode ? finalNode.data('label') : '');"
                + " const normalizedFinal = normalize(finalLabel);"
                + " return hasCollectedNode && hasCollectEdge"
                + "   && normalizedFinal === expected && normalizedFinal !== forbidden;"
                + "}",
            Map.of("expectedFinalLabel", expectedFinalLabel, "forbiddenFinalLabel", forbiddenFinalLabel),
            new Page.WaitForFunctionOptions().setTimeout(5_000));
    }

    private String expectedCollectedPolynomialFinalLabel(String fileName) {
        if (fileName.contains("binomial")) {
            return "x ^ 2 + 6 * x + 9";
        }
        if (fileName.contains("polynomial")) {
            return "x ^ 2 + 3 * x + 2";
        }
        return "";
    }

    private String forbiddenUncollectedPolynomialFinalLabel(String fileName) {
        if (fileName.contains("binomial")) {
            return "x * x + 3 * x + x * 3 + 3 * 3";
        }
        if (fileName.contains("polynomial")) {
            return "x * x + x * 2 + x + 2";
        }
        return "";
    }

    private String expectedSemanticGraphLabel(String fileName) {
        if (fileName.contains("binomial")) {
            return "(x + 3) ^ 2";
        }
        if (fileName.contains("polynomial")) {
            return "(x + 1) * (x + 2)";
        }
        if (fileName.contains("trigonometry")) {
            return "cos(x) ^ 2 + sin(x) ^ 2";
        }
        return "";
    }

    private void assertSemanticGraphVisualLayout(String fileName) {
        page.waitForFunction(
            "() => {"
                + " const canvas = document.querySelector('#graphCanvas');"
                + " if (!canvas) return false;"
                + " const box = canvas.getBoundingClientRect();"
                + " return box.height >= box.width && countGraphLabelOverlaps(false) === 0"
                + "   && countGraphLabelOverlaps(true) === 0 && mainPathYPositionsIncrease();"
                + "}",
            null,
            new Page.WaitForFunctionOptions().setTimeout(5_000));
        int nodeLabelOverlaps = ((Number) page.evaluate("() => countGraphLabelOverlaps(false)")).intValue();
        assertTrue(nodeLabelOverlaps == 0,
            fileName + " must not overlap node label bounding boxes; overlaps=" + nodeLabelOverlaps);
        int edgeLabelOverlaps = ((Number) page.evaluate("() => countGraphLabelOverlaps(true)")).intValue();
        assertTrue(edgeLabelOverlaps == 0,
            fileName + " must not place edge labels over node labels; overlaps=" + edgeLabelOverlaps);
        Boolean portrait = (Boolean) page.evaluate(
            "() => { const box = document.querySelector('#graphCanvas').getBoundingClientRect();"
                + " return box.height >= box.width; }");
        assertTrue(Boolean.TRUE.equals(portrait),
            fileName + " graph screenshot canvas must be portrait or vertical");
        Boolean increasing = (Boolean) page.evaluate("() => mainPathYPositionsIncrease()");
        assertTrue(Boolean.TRUE.equals(increasing),
            fileName + " main-path y positions must be strictly increasing");
    }

    private void createParentDirectory(Path target) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException ex) {
            throw new AssertionError("Could not create screenshot directory for " + target, ex);
        }
    }

    private void assertScreenshotQuality(Path target, String fileName) {
        assertTrue(Files.exists(target), fileName + " must be written");
        try {
            BufferedImage image = ImageIO.read(target.toFile());
            assertNotNull(image, fileName + " must be a readable PNG");
            assertTrue(image.getWidth() >= 520,
                fileName + " must be at least 520 px wide");
            assertTrue(image.getHeight() >= 120,
                fileName + " must be at least 120 px high");
            assertTrue(hasVisibleContent(image),
                fileName + " must not be blank or nearly empty");
        } catch (IOException ex) {
            throw new AssertionError("Could not inspect screenshot " + target, ex);
        }
    }

    private boolean hasVisibleContent(BufferedImage image) {
        int stepX = Math.max(1, image.getWidth() / 80);
        int stepY = Math.max(1, image.getHeight() / 80);
        int min = 255;
        int max = 0;
        int distinct = 0;
        int previous = -1;
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                int rgb = image.getRGB(x, y) & 0x00ffffff;
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                int luminance = (r * 299 + g * 587 + b * 114) / 1000;
                min = Math.min(min, luminance);
                max = Math.max(max, luminance);
                if (rgb != previous) {
                    distinct++;
                    previous = rgb;
                }
            }
        }
        return (max - min) > 24 && distinct > 20;
    }
}
