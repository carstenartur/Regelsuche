package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Browser-driven tests for the simplified landing-page UX from PR #16
 * follow-up §3: tabs must stay hidden until the first search starts; goal
 * selection must round-trip into the search request; clicking a demo also
 * counts as starting a search.
 */
class LandingPageBrowserFlowTest {

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
    void close() {
        if (page != null) page.close();
        if (context != null) context.close();
    }

    @Test
    @DisplayName("Landing page presents a single primary entry flow")
    void landingPageShowsSimplePrimaryFlow() {
        // The workbench entry tab is always visible and active by default.
        assertTrue(page.locator(".tab[data-tab='workbench']").isVisible(),
            "the workbench entry tab must be visible on landing");
        assertTrue(page.locator(".tab[data-tab='workbench'].active").count() >= 1,
            "the workbench tab must be the active default");
        // The primary flow — input + goal + search button — is reachable
        // without first navigating anywhere else.
        assertTrue(page.locator("#searchForm").isVisible(),
            "the main search form must be on the landing page");
        // The goal dropdown surfaces the documented TransformationGoal options.
        String goalHtml = page.locator("#searchForm select[name='goal']")
            .innerHTML();
        for (String option : new String[] {
            "SIMPLIFY", "FACTORIZE", "TEACHING_FRIENDLY",
            "PROOF_FRIENDLY", "NUMERICALLY_STABLE"
        }) {
            assertTrue(goalHtml.contains(option),
                "goal dropdown must offer the " + option + " option");
        }
    }

    @Test
    @DisplayName("Tabs other than the entry one are hidden before the first search")
    void tabsHiddenBeforeFirstSearch() {
        assertEquals("true", page.locator("body").getAttribute("data-pre-search"),
            "body must start in pre-search state");
        // CSS hides downstream tabs; Playwright .isVisible() respects display:none.
        assertFalse(page.locator(".tab[data-tab='graph']").isVisible(),
            "graph tab must be hidden before the first search");
        assertFalse(page.locator(".tab[data-tab='replay']").isVisible(),
            "replay tab must be hidden before the first search");
        assertFalse(page.locator(".tab[data-tab='proofJobs']").isVisible(),
            "proof-jobs tab must be hidden before the first search");
    }

    @Test
    @DisplayName("Tabs become visible after a demo / search is started")
    void tabsVisibleAfterSearch() {
        // Click the binomial demo; this counts as starting the first search.
        page.locator(".demo-button[data-demo='binomial']").click();
        page.waitForFunction(
            "() => document.body.dataset.preSearch === 'false'",
            null, new Page.WaitForFunctionOptions().setTimeout(60_000));
        assertTrue(page.locator(".tab[data-tab='graph']").isVisible(),
            "graph tab must reveal after the first search");
        assertTrue(page.locator(".tab[data-tab='replay']").isVisible(),
            "replay tab must reveal after the first search");
        assertTrue(page.locator(".tab[data-tab='proofJobs']").isVisible(),
            "proof-jobs tab must reveal after the first search");
        assertTrue(page.locator(".tab[data-tab='exports']").isVisible(),
            "exports tab must reveal after the first search");
    }

    @Test
    @DisplayName("Goal selection is submitted with the search request")
    void goalSelectionIsSubmittedWithSearch() {
        // Capture the POST body to /api/search to assert the goal is forwarded.
        StringBuilder captured = new StringBuilder();
        page.route("**/api/search", route -> {
            captured.append(route.request().postData());
            route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setStatus(200)
                .setContentType("application/json")
                .setBody("{\"submitted\":true,\"acceptedAt\":\"2024-01-01T00:00:00Z\"}"));
        });
        page.locator("#searchForm input[name='expression']").fill("(x+1)^2");
        page.locator("#searchForm select[name='goal']").selectOption("TEACHING_FRIENDLY");
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction(
            "() => document.body.dataset.preSearch === 'false'",
            null, new Page.WaitForFunctionOptions().setTimeout(10_000));
        assertTrue(captured.toString().contains("TEACHING_FRIENDLY"),
            "search request must include the selected goal: " + captured);
    }

    @Test
    void primaryTasksAndKeyboardNavigationKeepToolsReachable() {
        assertTrue(page.locator("#workflowExplore").isVisible());
        assertTrue(page.locator("#workflowLearn").isVisible());
        assertEquals("/static/pair-reductions.html", page.locator("#workflowOptimize").getAttribute("href"));
        page.locator("#workflowLearn").click();
        assertTrue(page.locator("#tab-compare").isVisible());
        assertFalse(page.locator("#workflowEntry").isVisible(),
            "task entry cards belong to the Workbench, not the comparison view");
        assertFalse(page.locator("#demoHero").isVisible());
        assertTrue(page.url().endsWith("#tab=compare"));
        page.locator(".tab[data-tab='compare']").focus();
        page.keyboard().press("ArrowRight");
        assertEquals("true", page.locator(".tab[data-tab='inventory']").getAttribute("aria-selected"));
        assertTrue(page.locator("#tab-inventory").isVisible());
        page.reload();
        page.waitForLoadState(LoadState.NETWORKIDLE);
        assertTrue(page.locator("#tab-inventory").isVisible(), "a copied tool URL must reopen the same view");
    }

    @Test
    void workflowEntryStaysInWorkbenchWhileDemoContextSurvivesToolNavigation() {
        assertTrue(page.locator("#workflowEntry").isVisible());
        page.locator(".demo-button[data-demo='binomial']").click();
        page.waitForFunction("() => window.__regelsucheDemoReady === true");
        String summary = page.locator("#demoSummary").textContent();
        assertFalse(summary.isBlank(), "a successful demo supplies context for its graph and replay");

        for (String tool : new String[] {"graph", "replay"}) {
            page.locator(".tab[data-tab='" + tool + "']").click();
            assertFalse(page.locator("#workflowEntry").isVisible(), tool);
            assertTrue(page.locator("#demoHero").isVisible(), tool);
            assertTrue(page.locator("#demoSummary").isVisible(), tool);
            assertEquals(summary, page.locator("#demoSummary").textContent(), tool);
            assertTrue(page.locator("#tab-" + tool).isVisible(), tool);
        }

        page.locator(".tab[data-tab='compare']").click();
        assertFalse(page.locator("#workflowEntry").isVisible());
        assertFalse(page.locator("#demoHero").isVisible());
        assertEquals(summary, page.locator("#demoSummary").textContent());

        page.locator(".tab[data-tab='workbench']").click();
        assertTrue(page.locator("#workflowEntry").isVisible());
        assertTrue(page.locator("#demoHero").isVisible());
        assertEquals(summary, page.locator("#demoSummary").textContent());
    }

    @Test
    void searchSummaryShowsConditionsWithoutUpgradingExampleEvidence() {
        page.route("**/api/search", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
            .setStatus(200).setContentType("application/json").setBody("""
                {"expression":"x/x","bestExpression":"1","successes":1,
                 "assumptions":["x != 0","<img src=x onerror=alert(1)>"],
                 "proofStatus":"VALIDATED_BY_EXAMPLES"}
                """)));
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction("() => !document.getElementById('searchResultSummary').hidden");
        String result = page.locator("#searchResultSummary").innerText();
        assertTrue(result.contains("x/x → 1"), result);
        assertTrue(result.contains("x != 0"), result);
        assertTrue(result.contains("beweist keine allgemeine Gültigkeit"), result);
        assertEquals(0, page.locator("#searchResultSummary img").count(), "server strings must stay inert text");
        assertFalse(page.locator("#searchResponseDetails").evaluate("node => node.open").equals(Boolean.TRUE));
        page.locator("#searchResultSummary [data-open-tab='paths']").click();
        assertTrue(page.locator("#tab-paths").isVisible());
    }

    @Test
    void searchBudgetAndTechnicalFailureRemainDistinctFromDisproof() {
        page.route("**/api/search", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
            .setStatus(200).setContentType("application/json").setBody("""
                {"expression":"x+x","bestExpression":null,"successes":0,"stopReason":"NODE_BUDGET",
                 "diagnostics":[{"ruleId":"broken","stage":"APPLY","message":"test failure"}]}
                """)));
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction("() => !document.getElementById('searchResultSummary').hidden");
        String result = page.locator("#searchResultSummary").innerText();
        assertTrue(result.contains("NODE_BUDGET"), result);
        assertTrue(result.contains("keine Angaben zu Voraussetzungen"), result);
        page.locator("#searchResultSummary details summary").click();
        assertTrue(page.locator("#searchResultSummary").innerText().contains("broken · APPLY · test failure"));
        page.unroute("**/api/search");
        page.route("**/api/search", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
            .setStatus(500).setContentType("text/plain").setBody("Executor unavailable")));
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction("() => document.getElementById('searchResultSummary').textContent.includes('Executor unavailable')");
        assertTrue(page.locator("#searchResultSummary").innerText().contains("kein mathematisches Gegenbeispiel"));
    }

    @Test
    void deepLinksLoadDataAndCanLeaveARetainedRun() {
        var requests = new AtomicInteger();
        page.route("**/api/inventory", route -> {
            requests.incrementAndGet();
            route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setStatus(200).setContentType("application/json").setBody("{\"rules\":[]}"));
        });
        page.navigate(app.baseUrl() + "#tab=inventory");
        page.waitForFunction("() => document.getElementById('inventoryList').dataset.loaded === '1'");
        assertEquals(1, requests.get(), "deep link must run the ordinary tab data loader");
        page.reload();
        page.waitForFunction("() => document.getElementById('inventoryList').dataset.loaded === '1'");
        assertEquals(2, requests.get(), "fresh navigation must load the requested view");
        page.evaluate("location.hash = '#run=invalid'");
        page.waitForFunction("() => document.body.classList.contains('retained-run')");
        page.evaluate("location.hash = '#tab=inventory'");
        page.waitForFunction("() => document.getElementById('tab-inventory').classList.contains('active')");
        assertFalse(page.locator("body").getAttribute("class").contains("retained-run"));
        assertTrue(page.locator("#tab-inventory").isVisible());
    }

    @Test
    void runtimeHeadlineUsesTheChosenCandidatesRequirements() {
        page.route("**/api/search", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
            .setStatus(200).setContentType("application/json").setBody("""
                {"schema":"regelsuche.safe-runtime-artifact/v1","evidence":{
                  "request":{"source":"log(a*b)","profile":"SAFE_PREPARATION_V4"},
                  "status":"CANDIDATES_FOUND","retainedAssumptions":[],"authority":{},
                  "outcomes":[{"status":"DIRECT","candidate":{"expression":"log(a)+log(b)",
                    "retainedAssumptions":["a > 0","b > 0"]}}]}}
                """)));
        page.locator("#runtimeProfile").selectOption("SAFE_PREPARATION_V4");
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction("() => !document.getElementById('searchResultSummary').hidden");
        String summary = page.locator("#searchResultSummary").innerText();
        assertTrue(summary.contains("a > 0") && summary.contains("b > 0"), summary);
        assertFalse(summary.contains("keine zusätzlichen Voraussetzungen"), summary);
    }

    @Test
    void supersededOrFailedPathLoadsRemainRetryable() {
        page.evaluate("""
            (() => {
              const originalFetch = window.fetch;
              window.pendingPaths = [];
              window.fetch = (url, options) => url === '/api/paths'
                ? new Promise(resolve => window.pendingPaths.push((data, status = 200) =>
                    resolve(new Response(JSON.stringify(data), {status, headers:{'Content-Type':'application/json'}}))))
                : url === '/api/search' ? Promise.resolve(new Response('current search failed', {status:500}))
                : originalFetch(url, options);
              window.RegelsucheWorkbench.activateTab('paths');
            })()
            """);
        page.waitForFunction("() => window.pendingPaths.length === 1");
        page.evaluate("window.RegelsucheWorkbench.activateTab('workbench')");
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction("() => document.getElementById('searchResultSummary').textContent.includes('current search failed')");
        page.evaluate("window.pendingPaths[0]({paths:[]})");
        page.evaluate("() => new Promise(requestAnimationFrame)");
        page.evaluate("window.RegelsucheWorkbench.activateTab('paths')");
        page.waitForFunction("() => window.pendingPaths.length === 2");
        page.evaluate("window.pendingPaths[1]({error:'paths unavailable'}, 500)");
        page.waitForFunction("() => document.getElementById('pathsList').textContent.includes('paths unavailable')");
        assertFalse("1".equals(page.locator("#pathsList").getAttribute("data-loaded")));
        page.evaluate("window.RegelsucheWorkbench.activateTab('workbench')");
        page.evaluate("window.RegelsucheWorkbench.activateTab('paths')");
        page.waitForFunction("() => window.pendingPaths.length === 3");
        page.evaluate("window.pendingPaths[2]({paths:[]})");
        page.waitForFunction("() => document.getElementById('pathsList').dataset.loaded === '1'");
        assertTrue(page.locator("#pathsList").innerText().contains("Keine Pfade"));
    }

    @Test
    void obsoleteSearchOrImportCannotOverwriteTheCurrentResult() {
        // Deliberately ignore AbortSignal to exercise the generation fence even
        // when cancellation cannot stop an already completed transport.
        page.evaluate("""
            (() => {
              const originalFetch = window.fetch;
              window.pendingSearches = [];
              window.fetch = (url, options) => url === '/api/search'
                ? new Promise(resolve => window.pendingSearches.push((data, status = 200) =>
                    resolve(new Response(JSON.stringify(data), {status, headers:{'Content-Type':'application/json'}}))))
                : originalFetch(url, options);
            })()
            """);
        page.locator("#searchForm input[name='expression']").fill("old + 0");
        page.locator("#searchForm button[type='submit']").click();
        page.locator("#searchForm input[name='expression']").fill("new + 0");
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction("() => window.pendingSearches.length === 2");
        page.evaluate("window.pendingSearches[1]({expression:'new + 0',bestExpression:'new',successes:1})");
        page.waitForFunction("() => document.getElementById('searchResultSummary').textContent.includes('new + 0 → new')");
        page.evaluate("window.pendingSearches[0]({expression:'old + 0',bestExpression:'old',successes:1})");
        page.evaluate("() => new Promise(requestAnimationFrame)");
        assertTrue(page.locator("#searchResultSummary").innerText().contains("new + 0 → new"));
        assertFalse(page.locator("#searchOutput").textContent().contains("old"));

        page.locator("#runtimeImport").setInputFiles(new com.microsoft.playwright.options.FilePayload(
            "pending.json", "application/json", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        page.waitForFunction("() => window.pendingSearches.length === 3");
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction("() => window.pendingSearches.length === 4");
        page.evaluate("window.pendingSearches[3]({expression:'new + 0',bestExpression:'new',successes:1})");
        page.waitForFunction("() => document.getElementById('searchResultSummary').textContent.includes('new + 0 → new')");
        page.evaluate("window.pendingSearches[2]({error:'late replay failure'}, 500)");
        page.evaluate("() => new Promise(requestAnimationFrame)");
        assertTrue(page.locator("#searchResultSummary").innerText().contains("new + 0 → new"));
        assertTrue(page.locator("#runtimeExport").isDisabled());
        assertFalse(page.locator("#searchStatus").innerText().contains("fehlgeschlagen"));
    }
}
