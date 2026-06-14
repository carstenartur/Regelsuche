package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
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
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    @AfterEach
    void close() {
        if (page != null) page.close();
        if (context != null) context.close();
    }

    @Test
    @DisplayName("Rule authoring workflow exposes position → match → apply end-to-end")
    void ruleAuthoringWorkflowWorksEndToEnd() {
        page.locator(".tab[data-tab='ruleIde']").click();
        page.locator("#inspectExpression").fill("sin(x^2+6*x+5)");
        page.locator("#inspectForm button[type='submit']").click();

        page.waitForFunction(
                "() => document.querySelectorAll('#inspectPositionList .inspect-pos-btn').length > 0",
                null,
                new Page.WaitForFunctionOptions().setTimeout(30_000));

        Locator positions = page.locator("#inspectPositionList .inspect-pos-btn");
        assertTrue(positions.count() > 0, "positions should be rendered");

        Locator quadraticPosition = page.locator("#inspectPositionList .inspect-pos-btn").filter(
                new Locator.FilterOptions().setHasText("000"));
        quadraticPosition.first().click();

        Locator matches = page.locator("#inspectMatchList .inspect-match");
        assertTrue(matches.count() > 0, "matches should be rendered for selected position");
        assertTrue(page.locator("#inspectMatchList .inspect-bindings").first().isVisible(), "bindings should be visible");
        assertTrue(page.locator("#inspectMatchList").innerText().contains("Gesamtausdruck nachher"),
                "expressionAfter preview should be visible");

        Locator completeSquareCard = page.locator("#inspectMatchList .inspect-match").filter(
                new Locator.FilterOptions().setHasText("COMPLETE_SQUARE"));
        completeSquareCard.first().locator(".inspect-apply-match").click();

        page.waitForFunction(
                "() => (document.getElementById('inspectExpression')?.value || '').includes('sin((x + 3) ^ 2 - 4)')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(30_000));

        String expression = page.locator("#inspectExpression").inputValue();
        assertTrue(expression.contains("sin((x + 3) ^ 2 - 4)"),
                "apply should update the working expression");
    }
}
