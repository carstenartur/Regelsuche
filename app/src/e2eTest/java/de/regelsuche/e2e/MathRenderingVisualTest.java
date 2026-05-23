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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MathRenderingVisualTest {

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
            .setViewportSize(1400, 900));
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
    @DisplayName("Visual baseline: demo summary math rendering stays stable")
    void demoSummaryMatchesBaseline() throws IOException {
        openDemo("math-matrix");
        page.waitForSelector("#demoSummary .math-matrix-panel .katex",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(15_000));
        ScreenshotDiffUtil.assertMatchesBaseline(page.locator("#demoSummary"), "demo-summary");
    }

    @Test
    @DisplayName("Visual baseline: replay block math rendering stays stable")
    void replayBlockMatchesBaseline() throws IOException {
        openDemo("binomial");
        openReplayForLatestPath();
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
        page.waitForSelector(".graph-overlay-layer .graph-node-math .katex",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(15_000));
        ScreenshotDiffUtil.assertMatchesBaseline(
            page.locator(".graph-overlay-layer .graph-node-math").first(),
            "search-graph-overlay");
    }

    @Test
    @DisplayName("Visual baseline: graph inspector math rendering stays stable")
    void inspectorMatchesBaseline() throws IOException {
        openDemo("binomial");
        openInteractiveGraph();
        page.waitForSelector(".graph-overlay-layer .graph-node-math .katex",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(15_000));
        page.evaluate("() => { const cy = window.__cyForTests; if (cy && cy.nodes().length) { cy.nodes()[0].emit('tap'); } }");
        page.waitForSelector("#graphInspector .katex",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(15_000));
        ScreenshotDiffUtil.assertMatchesBaseline(page.locator("#graphInspector"), "inspector");
    }

    private void openDemo(String demoId) {
        page.locator(".demo-button[data-demo='" + demoId + "']").click();
        page.waitForFunction(
            "() => document.querySelector('#demoSummary')"
                + " && document.querySelector('#demoSummary').innerHTML.length > 50",
            null,
            new Page.WaitForFunctionOptions().setTimeout(60_000));
    }

    private void openReplayForLatestPath() {
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
                + " s.selectedIndex = s.options.length - 1;"
                + " s.dispatchEvent(new Event('change', { bubbles: true }));"
                + "}");
        page.locator("#replayLoad").click();
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
    }
}
