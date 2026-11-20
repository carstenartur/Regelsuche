package de.regelsuche.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CertificateDemoBrowserFlowTest {
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

    @ParameterizedTest
    @ValueSource(ints = {1200, 390})
    void runsBothCertificatesFromTheWorkbench(int width) throws IOException {
        try (var context = browser.newContext(new Browser.NewContextOptions().setViewportSize(width, 900))) {
            var page = context.newPage();
            page.setDefaultTimeout(5_000);
            page.navigate(app.baseUrl());
            page.locator("#openCertificateDemos").click();
            assertThat(page.locator("h1")).hasText("Exakte Rechennachweise");

            var hall = page.locator("#hallResult");
            var montgomery = page.locator("#montgomeryResult");
            assertThat(hall).isHidden();
            assertThat(montgomery).isHidden();
            page.locator("#hallRun").press("Enter");
            assertThat(page.locator("#hallResultTitle")).isFocused();
            assertThat(page.locator("#hallCountDeficit")).hasText("0");
            assertThat(page.locator("#hallDeficit")).hasText("2");
            assertThat(page.locator("#hallWitness")).hasText("{A₁, A₂, A₃}");
            assertThat(page.locator("#hallNeighbors")).hasText("{B₁}");
            assertThat(page.locator("#hallGraph")).containsText("B₂, B₃, B₄");
            assertThat(hall).containsText("Für diesen endlichen Graphen");
            assertThat(montgomery).isHidden();
            assertEquals(Boolean.TRUE, page.evaluate("""
                () => document.documentElement.scrollWidth === document.documentElement.clientWidth
                """));

            String suffix = width == 390 ? "-mobile.png" : ".png";
            DocumentationScreenshots.capture(page, page.locator("#hall-demo"), "hall-deficit" + suffix,
                "Geprüftes Hall-Defizit mit Eingabegraph, Matching und Hall-Zeugen",
                "Vier Kandidaten, vier Blockierer, sechs Kanten, Kapazität eins je Blockierer");

            page.locator("#montgomeryRun").press("Enter");
            assertThat(page.locator("#montgomeryResultTitle")).isFocused();
            assertThat(page.locator("#montgomerySourceReductions")).hasText("4");
            assertThat(page.locator("#montgomeryTargetReductions")).hasText("1");
            assertThat(page.locator("#montgomeryAccumulator")).containsText("[−80.000, 80.000]");
            assertThat(montgomery).containsText("Gleichheit nach floorMod");
            assertThat(montgomery).containsText("Operationszahlen");
            assertThat(hall).isVisible();
            assertEquals(Boolean.TRUE, page.evaluate("""
                () => document.documentElement.scrollWidth === document.documentElement.clientWidth
                """));
            DocumentationScreenshots.capture(page, page.locator("#montgomery-demo"), "montgomery-planning" + suffix,
                "Geprüfter Montgomery-Plan mit Wortgrenzen und Beobachtung",
                "Vier Produkte mit q = 3329 und R = 2^16. Intervalle [-100, 100] und [-200, 200].");
        }
    }

    @Test
    void aFailedReplayClearsThePreviousResult() {
        try (var context = browser.newContext()) {
            var page = context.newPage();
            page.setDefaultTimeout(5_000);
            page.navigate(app.baseUrl() + "/static/certificate-demos.html");
            var button = page.locator("#hallRun");
            var result = page.locator("#hallResult");
            button.click();
            assertThat(result).isVisible();

            page.route("**/api/certificate-demos/hall",
                route -> route.abort());
            button.click();
            assertThat(result).isHidden();
            assertThat(page.locator("#hallStatus")).containsText("Kein geprüfter Nachweis");
            assertThat(button).isEnabled();
            assertThat(button).isFocused();
        }
    }
}
