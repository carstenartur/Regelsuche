package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Pixel-level visual regression tests for the math-rendering pipeline.
 *
 * <p>The tests are gated behind the {@code regelsuche.runVisualTests} system
 * property so ordinary local browser tests do not accidentally compare pixels
 * across unrelated host rendering stacks. The checkout-owned CI lifecycle runs
 * this class in {@code Dockerfile.visual-regression}, which pins the Playwright
 * browser image and exports actual and diff images as retained evidence.</p>
 *
 * <p>Refresh baselines only in that pinned container by using the explicit
 * {@code regelsuche.updateScreenshots=true} property. The versioned visual
 * regression policy and its contract test bind the environment, tolerances and
 * exact baseline identities.</p>
 */
@EnabledIfSystemProperty(named = "regelsuche.runVisualTests", matches = "true")
class MathRenderingVisualTest {

    static final int VIEWPORT_WIDTH = 1400;
    static final int VIEWPORT_HEIGHT = 900;
    static final double DEVICE_SCALE_FACTOR = 1.0;
    static final String LOCALE = "en-US";
    static final String TIMEZONE_ID = "UTC";
    static final int GRAPH_OVERLAY_STABLE_MILLIS = 250;

    private static RegelsucheAppEnvironment app;
    private static Playwright playwright;
    private static Browser browser;

    private BrowserContext context;
    private Page page;

    @BeforeAll
    static void boot() throws IOException {
        app = new RegelsucheAppEnvironment();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void teardown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (app != null) app.close();
    }

    @BeforeEach
    void openContext() {
        context = browser.newContext(new Browser.NewContextOptions()
            .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
            .setDeviceScaleFactor(DEVICE_SCALE_FACTOR)
            .setLocale(LOCALE)
            .setTimezoneId(TIMEZONE_ID));
        page = context.newPage();
        page.navigate(app.baseUrl());
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    @AfterEach
    void closeContext() {
        if (page != null) page.close();
        if (context != null) context.close();
    }

    @Test
    @DisplayName("Visual baseline: deterministic matrix panel stays stable")
    void demoSummaryMatchesBaseline() throws IOException {
        openDemo("math-matrix");
        page.waitForSelector("#demoSummary .math-matrix-panel .katex",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(15_000));
        ScreenshotDiffUtil.assertMatchesBaseline(
            page.locator("#demoSummary .math-matrix-panel"),
            "demo-summary");
    }

    @Test
    @DisplayName("Visual baseline: replay block math rendering stays stable")
    void replayBlockMatchesBaseline() throws IOException {
        openDemo("binomial");
        openReplayForSelectedDemoPath();
        page.waitForSelector("#replayCanvas .katex",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(15_000));
        ScreenshotDiffUtil.assertMatchesBaseline(
            page.locator(".replay-derivation-block"),
            "replay-block");
    }

    @Test
    @DisplayName("Visual baseline: search-graph overlays stay stable")
    void searchGraphOverlayMatchesBaseline() throws IOException {
        openDemo("binomial");
        openInteractiveGraph();
        ScreenshotDiffUtil.assertMatchesBaseline(
            page.locator(".graph-overlay-layer .graph-node-math").first(),
            "search-graph-overlay");
    }

    @Test
    @DisplayName("Visual baseline: graph inspector math rendering stays stable")
    void inspectorMatchesBaseline() throws IOException {
        openDemo("binomial");
        openInteractiveGraph();
        page.evaluate("() => { const cy = window.__cyForTests; if (cy && cy.nodes().length) { cy.nodes()[0].emit('tap'); } }");
        page.waitForSelector("#graphInspector .graph-inspector-math .katex",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(15_000));
        ScreenshotDiffUtil.assertMatchesBaseline(
            page.locator("#graphInspector .graph-inspector-math .katex").first(),
            "inspector");
    }

    private void openDemo(String demoId) {
        page.locator(".demo-button[data-demo='" + demoId + "']").click();
        page.waitForFunction(
            "() => document.querySelector('#demoSummary')"
                + " && document.querySelector('#demoSummary').innerHTML.length > 50",
            null,
            new Page.WaitForFunctionOptions().setTimeout(60_000));
    }

    @SuppressWarnings("unchecked")
    private void openReplayForSelectedDemoPath() {
        String pathId = (String) page.evaluate(
            "() => window.__lastSelectedPathId == null"
                + " ? null : String(window.__lastSelectedPathId)");
        assertTrue(pathId != null && !pathId.isBlank(),
            "Demo must expose its selected path id");

        // Prevent the tab's first-use lazy loader from racing with the exact
        // path selection below and replacing the selector after our fetch.
        page.evaluate("() => {"
            + " const select = document.querySelector('#replayPathSelect');"
            + " if (select) select.dataset.loaded = '1';"
            + "}");
        page.locator(".tab[data-tab='replay']").click();
        page.waitForSelector("#tab-replay.active",
            new Page.WaitForSelectorOptions().setTimeout(5_000));

        Map<String, Object> selection = (Map<String, Object>) page.evaluate(
            "async pathId => {"
                + " const select = document.querySelector('#replayPathSelect');"
                + " if (!select) return { ok: false, diagnostic: 'missing replay selector' };"
                + " const response = await fetch('/api/paths?sort=score');"
                + " const raw = await response.text();"
                + " if (!response.ok) return { ok: false, diagnostic: 'HTTP '"
                + "   + response.status + ': ' + raw };"
                + " let data;"
                + " try { data = JSON.parse(raw); }"
                + " catch (error) { return { ok: false, diagnostic: 'invalid JSON: ' + error }; }"
                + " const path = (data.transformations || []).find(candidate =>"
                + "   String(candidate.id) === String(pathId));"
                + " if (!path) return { ok: false, diagnostic: 'path not found: ' + pathId };"
                + " select.innerHTML = '';"
                + " const option = document.createElement('option');"
                + " option.value = String(path.id);"
                + " option.textContent = String(path.id);"
                + " select.appendChild(option);"
                + " select.value = String(path.id);"
                + " select.dispatchEvent(new Event('change', { bubbles: true }));"
                + " window.__regelsucheReplayReady = false;"
                + " const canvas = document.querySelector('#replayCanvas');"
                + " if (canvas) canvas.innerHTML = '';"
                + " return { ok: true, pathId: String(path.id) };"
                + "}",
            pathId);
        assertTrue(Boolean.TRUE.equals(selection.get("ok")),
            "Could not select exact replay path " + pathId + ": "
                + selection.get("diagnostic"));

        page.locator("#replayLoad").click();
        page.waitForFunction(
            "pathId => {"
                + " const select = document.querySelector('#replayPathSelect');"
                + " return window.__regelsucheReplayReady === true"
                + "   && select && String(select.value) === String(pathId);"
                + "}",
            pathId,
            new Page.WaitForFunctionOptions().setTimeout(15_000));
        page.waitForSelector(".replay-step",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(15_000));
    }

    private void openInteractiveGraph() {
        page.locator(".tab[data-tab='graph']").click();
        page.locator("#graphInteractive").check();
        page.locator("#reloadGraph").click();
        page.waitForSelector("#graphCanvas",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(15_000));
        assertTrue(page.locator("#graphCanvas").isVisible());
        page.waitForSelector(".graph-overlay-layer .graph-node-math .katex",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(15_000));
        waitForGraphOverlayToSettle();
    }

    private void waitForGraphOverlayToSettle() {
        page.waitForFunction(
            "settleMillis => {"
                + " const host = document.querySelector("
                + "   '.graph-overlay-layer .graph-node-math');"
                + " if (!host || !host.querySelector('.katex')) return false;"
                + " const rect = host.getBoundingClientRect();"
                + " const style = window.getComputedStyle(host);"
                + " const signature = [style.transform, style.opacity,"
                + "   rect.left.toFixed(3), rect.top.toFixed(3),"
                + "   rect.width.toFixed(3), rect.height.toFixed(3)].join('|');"
                + " const now = performance.now();"
                + " const state = window.__regelsucheVisualOverlayStability;"
                + " if (!state || state.signature !== signature) {"
                + "   window.__regelsucheVisualOverlayStability = { signature, since: now };"
                + "   return false;"
                + " }"
                + " return now - state.since >= settleMillis;"
                + "}",
            GRAPH_OVERLAY_STABLE_MILLIS,
            new Page.WaitForFunctionOptions().setTimeout(15_000));
    }
}
