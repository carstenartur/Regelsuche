package de.regelsuche.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.io.IOException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PairReductionBrowserFlowTest {
    private static final String SAMPLE_INPUT = "Symmetrische Paarsumme (x_i-x_j)^2, "
        + "Hochrechnung für 100.000 Zeilen, Beispielwerte 2, -1, 5";
    private static RegelsucheAppEnvironment app;
    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void boot() throws IOException {
        app = new RegelsucheAppEnvironment();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void shutdown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (app != null) app.close();
    }

    @Test
    void showsTheVerifiedDefaultPlanWithItsExactSample() throws IOException {
        try (BrowserContext context = viewport(1200)) {
            Page page = open(context);
            verifyDefaultResult(page);

            var derivation = page.locator("#pairResult summary");
            derivation.click();
            assertThat(page.locator("#pairExpanded")).isVisible();
            assertThat(page.locator("#pairResult")).containsText("symbolische Zeilen");
            DocumentationScreenshots.capture(page, page.locator("#pairResult"), "pair-reduction-result.png",
                "Geprüfte Paarsumme mit aufgeklappter Herleitung", SAMPLE_INPUT);
        }
    }

    @Test
    void keepsTheVerifiedResultAndDerivationReachableByKeyboardAt390Pixels() throws IOException {
        try (BrowserContext context = viewport(390)) {
            Page page = open(context);
            page.locator("#pairRows").focus();
            page.keyboard().press("Tab");
            assertThat(page.locator("#pairSubmit")).isFocused();
            verifyDefaultResult(page);

            var derivation = page.locator("#pairResult summary");
            page.keyboard().press("Tab");
            assertThat(derivation).isFocused();
            page.keyboard().press("Enter");
            assertThat(page.locator("#pairExpanded")).isVisible();
            assertEquals(Boolean.TRUE, page.evaluate("""
                () => document.documentElement.scrollWidth === document.documentElement.clientWidth
                """));
            DocumentationScreenshots.capture(page, page.locator("#pairResult"), "pair-reduction-mobile.png",
                "Geprüfte Paarsumme bei 390 Pixeln mit per Tastatur geöffneter Herleitung", SAMPLE_INPUT);
        }
    }

    @Test
    void inputChangesAndRejectedRequestsHideAnyEarlierSuccess() {
        try (BrowserContext context = viewport(1200)) {
            Page page = open(context);
            submit(page);
            assertThat(page.locator("#pairResult")).isVisible();

            var size = page.locator("#pairCount");
            size.fill("-1");
            assertThat(page.locator("#pairResult")).isHidden();
            submit(page);
            assertThat(size).isFocused();
            assertEquals(Boolean.FALSE, size.evaluate("field => field.validity.valid"));
            assertThat(page.locator("#pairResult")).isHidden();

            size.fill("100000");
            submit(page);
            assertThat(page.locator("#pairResult")).isVisible();
            page.locator("#pairSummand").fill("x_i");
            assertThat(page.locator("#pairResult")).isHidden();
            submit(page);
            assertThat(page.locator("#pairStatus")).containsText("Vertauschen");
            assertThat(page.locator("#pairResult")).isHidden();
            assertThat(page.locator("#pairIndexedResult")).isHidden();
        }
    }

    @Test
    void weightedOrderedAndWindowPresetsEvaluateTheirOwnSamples() {
        try (BrowserContext context = viewport(1200)) {
            Page page = open(context);
            var example = page.locator("#pairExample");
            var mode = page.locator("#pairMode");
            var symmetricResult = page.locator("#pairResult");
            var indexedResult = page.locator("#pairIndexedResult");

            example.selectOption("weighted");
            submit(page);
            assertThat(page.locator("#pairSample")).containsText("-99");
            mode.selectOption("ordered");
            assertThat(symmetricResult).isHidden();

            example.selectOption("chain");
            assertThat(mode).hasValue("ordered");
            submit(page);
            assertThat(indexedResult).isVisible();
            assertThat(page.locator("#pairIndexedTitle")).isFocused();
            assertThat(page.locator("#pairIndexedSample")).containsText(Pattern.compile("\\b20\\b"));

            example.selectOption("band");
            assertThat(indexedResult).isHidden();
            assertThat(mode).hasValue("windows");
            assertThat(page.locator("#pairWidth")).hasValue("3");
            submit(page);
            assertThat(indexedResult).isVisible();
            assertThat(page.locator("#pairIndexedSample")).containsText("96, 10");

            mode.selectOption("symmetric");
            assertThat(indexedResult).isHidden();
            assertThat(symmetricResult).isHidden();
        }
    }

    @Test
    void emptySampleLeavesOnlyAPlanAndProjection() {
        try (BrowserContext context = viewport(1200)) {
            Page page = open(context);
            page.locator("#pairRows").fill("");
            submit(page);
            assertThat(page.locator("#pairResult")).isVisible();
            assertThat(page.locator("#pairSample")).isHidden();
            assertThat(page.locator("#pairResult")).containsText("Hochrechnung");
            assertThat(page.locator("#pairResult")).containsText("Keine Beispieldaten");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1200, 390})
    void executesTheLargeDistanceDemoAndShowsItsDirectPrefixCheck(int width) throws IOException {
        try (BrowserContext context = viewport(width)) {
            Page page = open(context);
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions()
                .setName("Zum Lauf mit 100.000 Zeilen")).click();
            assertThat(page).hasURL(Pattern.compile("#pair-large-demo$"));
            var large = page.locator("#pairLargeResult");
            assertThat(large).isHidden();
            var run = page.locator("#pairLargeRun");
            assertThat(run).isEnabled();
            run.press("Enter");
            assertThat(page.locator("#pairLargeTitle")).isFocused();
            assertThat(page.locator("#pairLargeRows")).hasText("100.000");
            assertThat(page.locator("#pairLargeValue")).hasText("8.333.333.332.500.000.000");
            assertThat(page.locator("#pairLargeBaseline")).hasText("4.999.950.000");
            assertThat(large).containsText("Rechnerische Paaranzahl");
            assertThat(large).containsText("2.016");
            assertThat(large).containsText("1.397.760");
            assertEquals(Boolean.TRUE, page.evaluate("""
                () => document.documentElement.scrollWidth === document.documentElement.clientWidth
                """));
            String suffix = width == 390 ? "-mobile.png" : ".png";
            DocumentationScreenshots.capture(page, page.locator("#pair-large-demo"), "pair-reduction-large" + suffix,
                "Ausgeführter Ein-Pass-Plan über 100.000 Zeilen mit direkter Präfixkontrolle",
                "Quadrierter Abstand mit x_i = i - 50000 für 0 ≤ i < 100000. Direkter Vergleich der ersten 64 Werte.");
        }
    }

    private static BrowserContext viewport(int width) {
        return browser.newContext(new Browser.NewContextOptions().setViewportSize(width, 900));
    }

    private static Page open(BrowserContext context) {
        Page page = context.newPage();
        page.setDefaultTimeout(5_000);
        page.navigate(app.baseUrl() + "/static/pair-reductions.html");
        return page;
    }

    private static void submit(Page page) {
        page.locator("#pairSubmit").press("Enter");
    }

    private static void verifyDefaultResult(Page page) {
        assertThat(page.locator("#pairResult")).isHidden();
        submit(page);
        assertThat(page.locator("#pairOriginal")).hasText("4.999.950.000");
        assertThat(page.locator("#pairLinear")).hasText("100.000");
        assertThat(page.locator("#pairSample")).containsText(Pattern.compile("\\b54\\b"));
        assertThat(page.locator("#pairResultTitle")).isFocused();
        assertThat(page.locator("#pairSample")).containsText("2, -1, 5");
        assertThat(page.locator("#pairFormula")).isVisible();
        assertThat(page.locator("#pairFormula")).containsText("S1");
        assertThat(page.locator("#pairAccumulators")).containsText("S1 = Summe von x");
    }
}
