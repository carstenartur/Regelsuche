package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class BenchmarkSavingsBrowserTest {
    @Test
    void benchmarkDoesNotPresentLegacyRatiosAsMeasuredSavings() throws IOException {
        try (var app = new RegelsucheAppEnvironment();
             var playwright = Playwright.create();
             var browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             var page = browser.newPage()) {
            page.route("**/api/benchmark", route -> route.fulfill(
                new com.microsoft.playwright.Route.FulfillOptions()
                    .setContentType("application/json").setBody("""
                    {"scenarios":[{"name":"known-identities","results":[
                      {"strategy":"legacy","expression":"x + 0","eGraphClasses":2,"eGraphNodes":3,
                       "rulesFired":1,"saturationSavings":1.0},
                      {"strategy":"current","expression":"x + 0","eGraphClasses":2,"eGraphNodes":3,
                       "rulesFired":1,"saturationSavings":null,"saturationSavingsStatus":"UNAVAILABLE"}
                    ]}]}
                    """)));
            page.navigate(app.baseUrl());
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            // Activate the tab directly: this fixture tests reporting, not the landing workflow.
            page.locator(".tab[data-tab='benchmark']").evaluate("element => element.click()");
            page.locator("#reloadBenchmark").click();
            page.locator("#benchmarkSuites .benchmark-table tbody tr").first().waitFor();
            var rows = page.locator("#benchmarkSuites .benchmark-table tbody tr");
            assertEquals(2, rows.count());
            for (int i = 0; i < rows.count(); i++) {
                String text = rows.nth(i).innerText();
                assertTrue(text.contains("nicht gemessen"));
                assertTrue(text.contains("2 / 3"));
                assertFalse(text.contains("100.0%"));
            }
        }
    }
}
