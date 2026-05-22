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
}
