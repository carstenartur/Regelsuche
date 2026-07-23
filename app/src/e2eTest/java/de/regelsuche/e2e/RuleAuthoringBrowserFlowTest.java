package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
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

/** Preserves the original position → match → preview → apply workflow on the radar UI. */
class RuleAuthoringBrowserFlowTest {

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
    }

    @AfterAll
    static void teardown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (app != null) app.close();
    }

    @BeforeEach
    void openContext() {
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 900));
        page = context.newPage();
        page.navigate(app.baseUrl());
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
    }

    @AfterEach
    void close() {
        if (page != null) page.close();
        if (context != null) context.close();
    }

    @Test
    @DisplayName("AST radar preserves position → match → preview → apply end-to-end")
    void ruleAuthoringWorkflowWorksEndToEnd() {
        page.locator(".tab[data-tab='ruleIde']").click();
        page.waitForSelector("#radarExpression",
            new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(20_000));
        page.locator("#radarExpression").fill("sin(x^2+6*x+5)");
        page.locator("#radarInspect").click();

        // The radar performs an automatic inspection during initialization. Waiting
        // merely for any row can therefore observe the default expression instead
        // of the explicitly requested nested quadratic. Synchronize on the
        // expression-specific result that this flow is intended to exercise.
        page.waitForFunction(
            "() => {"
                + " const status = document.querySelector('#radarStatus');"
                + " const rows = Array.from(document.querySelectorAll('#radarCandidateRows tr'));"
                + " return status"
                + "   && !status.textContent.includes('werden im Backend berechnet')"
                + "   && rows.some(row => row.textContent.includes('COMPLETE_SQUARE'));"
                + " }",
            null,
            new Page.WaitForFunctionOptions().setTimeout(30_000));

        Locator candidates = page.locator("#radarCandidateRows tr");
        assertTrue(candidates.count() > 0, "position-bound candidates should be rendered");
        assertTrue(page.locator("#radarTree [role='treeitem']").count() > 0,
            "the expression AST must expose accessible tree items");

        Locator completeSquareRow = candidates.filter(
            new Locator.FilterOptions().setHasText("COMPLETE_SQUARE"));
        assertTrue(completeSquareRow.count() > 0,
            "complete-square should be enumerated at the nested quadratic subtree");
        completeSquareRow.first().locator("button").click();

        String detail = page.locator("#radarCandidateDetail").innerText();
        assertTrue(detail.contains("Candidate-ID"));
        assertTrue(detail.contains("Bindung"), "bindings should be visible outside the SVG");
        assertTrue(detail.contains("Vollständiger Zustand"), "full expression preview should be visible");
        assertTrue(page.locator("#radarApply").isEnabled());

        String before = page.locator("#radarExpression").inputValue();
        page.locator("#radarApply").click();
        page.waitForFunction(
            "before => document.querySelector('#radarExpression').value !== before",
            before,
            new Page.WaitForFunctionOptions().setTimeout(30_000));

        String after = page.locator("#radarExpression").inputValue();
        assertFalse(after.equals(before));
        assertTrue(after.contains("sin"));
        assertTrue(after.contains("^ 2") || after.contains("^2"),
            "apply should preserve the function context and introduce the square representation");
    }
}
