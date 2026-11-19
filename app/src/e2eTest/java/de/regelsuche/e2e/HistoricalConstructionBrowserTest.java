package de.regelsuche.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalConstructionBrowserTest {
    @Test
    void showsTheComputedHistoricalWitnessAndOpensItsExactReplayOnDesktopAndMobile() throws Exception {
        try (var app = new RegelsucheAppEnvironment();
             Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             var context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1140, 1080))) {
            Page page = context.newPage();
            page.setDefaultTimeout(10_000);
            page.navigate(app.baseUrl() + "/");
            // Keep an unrelated path in the store: the historical action must select its own witness.
            page.waitForResponse(response -> response.url().endsWith("/api/demo/math-matrix"),
                () -> page.locator("[data-demo='math-matrix']").click());
            page.waitForFunction("window.__regelsucheDemoReady === true");
            var response = page.waitForResponse(r -> r.url().endsWith("/api/demo/historical-sophie-germain"),
                () -> page.locator("#historicalSophieGermain").press("Enter"));
            assertEquals(200, response.status());
            JsonNode run = new ObjectMapper().readTree(response.text());
            var summary = page.locator("#historicalConstruction");
            assertThat(summary).containsText("Sophie-Germain");
            assertThat(summary).containsText("Faktorisierung gefunden");
            assertThat(summary).containsText("5 geprüfte Schritte");
            assertThat(summary).containsText("ohne vorgegebene Zielformel");
            assertThat(summary.locator("[data-construction-work]")).hasText(
                java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY)
                    .format(run.path("constructionSummary").path("totalWork").asLong()));
            var steps = summary.locator("[data-construction-step]");
            assertThat(steps).hasCount(5);
            assertThat(summary.locator("[data-construction-result] .math")).hasAttribute("aria-label",
                run.path("selectedPath").path("improvedExpression").asText());
            for (int index = 0; index < 5; index++) {
                JsonNode actual = run.path("constructionSummary").path("steps").get(index);
                assertThat(steps.nth(index)).hasAttribute("data-source-expression", actual.path("sourceExpression").asText());
                assertThat(steps.nth(index).locator(".historical-step-formula .math"))
                    .hasAttribute("aria-label", actual.path("targetExpression").asText());
                assertThat(steps.nth(index)).containsText("Exakt geprüft");
            }
            assertThat(steps.nth(2)).containsText("Quadrat ergänzen");
            assertTrue(summary.locator(".katex").count() >= 7);
            assertNoPageOverflow(page);
            String description = "x^4+4*y^4; FACTORIZATION without a supplied target; "
                + "five independently checked witness steps; logical work "
                + run.path("constructionSummary").path("totalWork").asLong();
            Path evidence = Path.of("build/reports/historical-construction");
            Files.createDirectories(evidence);
            Files.writeString(evidence.resolve("run.json"), run.toPrettyString());
            summary.screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions()
                .setPath(evidence.resolve("desktop.png")));
            DocumentationScreenshots.capture(page, summary, "historical-sophie-germain.png",
                "Computed Sophie-Germain factorization and complete checked witness", description);

            steps.nth(2).locator("[data-construction-replay]").press("Enter");
            assertThat(page.locator("#tab-replay")).hasClass("tab-panel active");
            assertThat(page.locator("#replayPathSelect")).hasValue(run.path("selectedPath").path("id").asText());
            assertThat(page.locator(".replay-step-index")).hasText("Schritt 3 / 5");
            assertThat(page.locator(".replay-from code")).hasText(
                run.path("constructionSummary").path("steps").get(2).path("sourceExpression").asText());
            assertThat(page.locator(".replay-to code")).hasText(
                run.path("constructionSummary").path("steps").get(2).path("targetExpression").asText());
            assertThat(page.locator(".replay-dashboard-metrics")).containsText("Schritte im Pfad");

            page.locator(".tab[data-tab='workbench']").click();
            page.setViewportSize(390, 844);
            assertNoPageOverflow(page);
            assertThat(steps.nth(2).locator(".historical-step-formula")).isVisible();
            summary.screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions()
                .setPath(evidence.resolve("mobile.png")));
            DocumentationScreenshots.capture(page, summary, "historical-sophie-germain-mobile.png",
                "Computed Sophie-Germain factorization on a narrow screen", description);
            steps.nth(2).locator("summary").press("Enter");
            assertThat(steps.nth(2).locator(".historical-verification")).isVisible();
            assertThat(steps.nth(2).locator(".historical-verification")).containsText(
                run.path("constructionSummary").path("steps").get(2).path("provenanceHash").asText());
            assertNoPageOverflow(page);
            steps.nth(4).locator("[data-construction-replay]").press("Enter");
            assertThat(page.locator(".replay-step-index")).hasText("Schritt 5 / 5");
            assertEquals(run.path("selectedPath").path("id").asText(), page.locator("#replayPathSelect").inputValue());
        }
    }

    private static void assertNoPageOverflow(Page page) {
        assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth"),
            "The historical result must fit the viewport; long math may scroll within its labelled formula region");
    }
}
