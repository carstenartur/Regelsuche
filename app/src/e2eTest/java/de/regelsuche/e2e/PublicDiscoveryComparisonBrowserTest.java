package de.regelsuche.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicDiscoveryComparisonBrowserTest {
    @Test
    void recordsTheActualPairedTaskAndRetainsNegativeControls() throws Exception {
        try (var app = new RegelsucheAppEnvironment();
             Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             var context = browser.newContext(new Browser.NewContextOptions().setViewportSize(900, 1000))) {
            Page page = context.newPage();
            page.navigate(app.baseUrl() + "/static/public-discovery-comparison.html");
            page.locator("#qualification .case-group > summary").first().click();
            var example = page.locator("#qualificationPositives details").first();
            example.locator("summary").click();
            assertThat(example).containsText("26 → 3 explored states");
            assertThat(example.locator("dd").first()).hasText("REACHED → REACHED");
            assertThat(example.locator("dd").nth(1)).hasText("3 → 1");
            assertThat(example.getByText("(20 + 2) * (i * j + k) + 20 * (i * j + k)",
                new Locator.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.locator("#qualification")).containsText("12/12 negative controls passed");
            String inputDescription = "(20 + 2) * (i * j + k) + 20 * (i * j + k); "
                + "both arms reach the target, 26/3 states and 3/1 steps";
            DocumentationScreenshots.capture(page, page.locator("#qualification"),
                "learned-rule-comparison.png", "Recorded composite-factor qualification, affine-factor-20",
                inputDescription);
            page.setViewportSize(390, 844);
            assertThat(example).containsText("26 → 3 explored states");
            assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth"));
            DocumentationScreenshots.capture(page, example, "learned-rule-comparison-mobile.png",
                "Recorded composite-factor qualification, affine-factor-20 on a narrow screen",
                inputDescription);
        }
    }

    @Test
    void opensBothRecordedComparisonsByKeyboardOnMobile() throws Exception {
        try (var app = new RegelsucheAppEnvironment();
             Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             var context = browser.newContext(new Browser.NewContextOptions().setViewportSize(390, 844))) {
            Page page = context.newPage();
            page.navigate(app.baseUrl() + "/");
            page.locator("#openPublicDiscoveryComparison").press("Enter");

            assertThat(page.locator("#qualification")).containsText("423 → 32");
            assertThat(page.locator("#qualification")).containsText("12/12 showed a material gain");
            assertThat(page.locator("#qualification")).containsText("12/12 negative controls passed");
            assertThat(page.locator("#qualificationControls details")).hasCount(12);
            page.locator("#qualification .case-group").first().locator(":scope > summary").press("Enter");
            var positive = page.locator("#qualificationPositives details").first();
            positive.locator("summary").press("Enter");
            assertThat(positive).containsText("26 → 3 explored states");
            assertThat(positive.getByText("(2 * 20 + 2) * (i * j + k)",
                new Locator.GetByTextOptions().setExact(true)).first()).isVisible();

            assertThat(page.locator("#transfer")).containsText("53 matcher calls · 0 applications");
            var trig = page.locator("#transfer-trigonometric");
            trig.locator("summary").press("Enter");
            assertThat(trig).containsText("Held-out family");
            assertThat(trig.locator(".retained-path").nth(0)).containsText("ast_factor_common_left");
            assertThat(trig.locator(".retained-path").nth(1)).containsText("ast_factor_common_left");
            assertThat(trig).containsText("DEPTH_BUDGET");
            assertThat(trig).containsText("NOT_ELIGIBLE");
            assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"));

            page.goBack();
            assertThat(page.locator("#openPublicDiscoveryComparison")).isVisible();
            page.navigate(app.baseUrl() + "/static/index.html#tab=runs");
            page.locator("#openPublicDiscoveryFromRuns").press("Enter");
            assertThat(page.locator("#transfer")).isVisible();
        }
    }
}
