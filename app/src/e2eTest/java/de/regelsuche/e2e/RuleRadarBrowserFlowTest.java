package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives the production workbench and proves that API candidates, radial AST
 * points, keyboard interaction, explicit preview/application and correlated
 * search edges form one coherent user flow.
 */
class RuleRadarBrowserFlowTest {
    private static final boolean RECORD_DOCS = Boolean.parseBoolean(
        System.getProperty("regelsuche.recordDocs", "false"));
    private static final Path SCREENSHOT = Paths.get("..", "docs", "assets", "screenshots", "ast-rule-radar.png")
        .toAbsolutePath().normalize();
    private static final Pattern CANDIDATE_COUNT = Pattern.compile(", (\\d+) Anwendungen$");

    private static RegelsucheAppEnvironment app;
    private static Playwright playwright;
    private static Browser browser;
    private BrowserContext context;
    private Page page;

    @BeforeAll
    static void boot() throws IOException {
        app = new RegelsucheAppEnvironment();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        if (RECORD_DOCS) {
            Files.createDirectories(SCREENSHOT.getParent());
        }
    }

    @AfterAll
    static void shutdown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (app != null) app.close();
    }

    @BeforeEach
    void open() {
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1500, 1050));
        page = context.newPage();
        page.navigate(app.baseUrl());
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        page.locator(".tab[data-tab='ruleIde']").click();
        page.waitForSelector("#tab-ruleIde.active .radar-move-point",
            new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(20_000));
    }

    @AfterEach
    void close() {
        if (page != null) page.close();
        if (context != null) context.close();
    }

    @Test
    @DisplayName("AST-Regelradar: candidates, keyboard, apply and search correlation")
    void completeRuleRadarFlow() throws IOException {
        Locator points = page.locator("#radarTree .radar-move-point");
        Locator rows = page.locator("#radarCandidateRows tr");
        assertTrue(points.count() > 0);
        assertEquals(rows.count(), points.count(),
            "radial point count must equal the accessible candidate table count");
        assertTrue(points.first().getAttribute("aria-label").contains("Position"));
        assertEquals("button", points.first().getAttribute("role"));
        assertEquals("0", points.first().getAttribute("tabindex"));

        Locator treeNodes = page.locator("#radarTree .radar-ast-node");
        for (int index = 0; index < treeNodes.count(); index++) {
            Locator node = treeNodes.nth(index);
            Matcher matcher = CANDIDATE_COUNT.matcher(node.getAttribute("aria-label"));
            assertTrue(matcher.find());
            assertEquals(Integer.parseInt(matcher.group(1)), node.locator(".radar-move-point").count(),
                "each visual circle must contain exactly its advertised API candidates");
        }

        // Focus alone exposes the full details and a projected global edge;
        // activation remains a separate explicit action.
        points.first().focus();
        page.waitForSelector("#radarCandidateDetail code",
            new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
        assertTrue(page.locator("#radarCandidateDetail").innerText().contains("Candidate-ID"));
        assertTrue(page.locator("#radarCandidateDetail").innerText().contains("Vollständiger Zustand"));
        assertTrue(page.locator("#radarProjectedEdge").innerText().contains("Projizierte Suchkante"));
        page.keyboard().press("Enter");

        // Select the deterministic root simplification from the equivalent
        // table surface and apply only after the preview is visible.
        Locator addZeroRow = page.locator("#radarCandidateRows tr")
            .filter(new Locator.FilterOptions().setHasText("ast_add_zero_right"));
        assertTrue(addZeroRow.count() >= 1);
        addZeroRow.first().locator("button").click();
        assertTrue(page.locator("#radarCandidateDetail").innerText().contains("ast_add_zero_right"));
        assertTrue(page.locator("#radarApply").isEnabled());
        String before = page.locator("#radarExpression").inputValue();
        page.locator("#radarApply").click();
        page.waitForFunction("before => document.querySelector('#radarExpression').value !== before", before,
            new Page.WaitForFunctionOptions().setTimeout(10_000));
        String after = page.locator("#radarExpression").inputValue();
        assertFalse(after.equals(before));
        assertFalse(after.endsWith("+ 0"));
        assertTrue(page.locator("#radarUndo").isEnabled());

        // Undo restores the state, then bounded search creates an edge carrying
        // the same candidate identity. States, edges and events all navigate
        // back to a synchronized AST snapshot and exact candidate position.
        page.locator("#radarUndo").click();
        page.waitForFunction("before => document.querySelector('#radarExpression').value === before", before,
            new Page.WaitForFunctionOptions().setTimeout(10_000));
        assertTrue(page.locator("#radarApplyStatus").innerText().contains("Rückgängig"));
        page.locator("#radarGoal").fill("(x + 1)^2");
        page.locator("#radarRunSearch").click();
        page.waitForSelector("#radarSearchGraph .radar-search-edge",
            new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(20_000));
        assertTrue(page.locator("#radarSearchGraph .radar-search-state").count() >= 2);
        page.locator("#radarSearchGraph .radar-search-state").last().click();
        assertFalse(page.locator("#radarExpression").inputValue().isBlank());

        page.locator("#radarSearchGraph .radar-search-edge").first().click();
        page.waitForFunction("() => document.querySelectorAll('#radarTree .radar-move-point.selected').length === 1",
            null, new Page.WaitForFunctionOptions().setTimeout(10_000));
        assertTrue(page.locator("#radarCandidateDetail").innerText().contains("Candidate-ID"));

        Locator event = page.locator("#radarSearchGraph [data-search-event-candidate]").last();
        assertTrue(event.count() == 1);
        event.click();
        page.waitForFunction("() => document.querySelectorAll('#radarTree .radar-move-point.selected').length === 1",
            null, new Page.WaitForFunctionOptions().setTimeout(10_000));

        if (RECORD_DOCS) {
            page.locator("#tab-ruleIde").screenshot(
                new Locator.ScreenshotOptions().setPath(SCREENSHOT).setAnimations(ScreenshotAnimations.DISABLED));
            assertTrue(Files.size(SCREENSHOT) > 10_000,
                "documentation screenshot must be generated by the browser test");
        }
    }
}
