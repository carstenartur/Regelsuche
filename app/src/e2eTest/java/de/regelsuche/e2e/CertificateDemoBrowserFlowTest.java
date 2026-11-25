package de.regelsuche.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
            var hallButton = page.locator("#hallRun");
            assertThat(hallButton).isEnabled();
            hallButton.press("Enter");
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

            var montgomeryButton = page.locator("#montgomeryRun");
            assertThat(montgomeryButton).isEnabled();
            montgomeryButton.press("Enter");
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

    @ParameterizedTest
    @CsvSource({
        "openCertificateDemos, hallRun, hallResultTitle",
        "openCertificateDemos, montgomeryRun, montgomeryResultTitle",
        "workflowOptimize, pairLargeRun, pairLargeTitle"
    })
    void runButtonsWaitForTheDemoModule(String entry, String runButton, String resultTitle) {
        try (var context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1200, 900))) {
            var page = context.newPage();
            page.setDefaultTimeout(5_000);
            var pending = new AtomicReference<Route>();
            page.route("**/certificate-demos.js", pending::set);
            page.navigate(app.baseUrl());
            page.locator("#" + entry).click();

            var button = page.locator("#" + runButton);
            assertThat(button).isDisabled();
            page.waitForCondition(() -> pending.get() != null);
            var module = pending.get();
            assertNotNull(module);
            module.resume();

            assertThat(button).isEnabled();
            button.press("Enter");
            assertThat(page.locator("#" + resultTitle)).isFocused();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1200, 390})
    void countsEditedCnfDownloadsAndRestoresDraft(int width) throws IOException {
        try (var context = browser.newContext(new Browser.NewContextOptions().setViewportSize(width, 900))) {
            var page = context.newPage();
            page.setDefaultTimeout(5_000);
            page.navigate(app.baseUrl() + "/static/certificate-demos.html");
            var run = page.locator("#cnfRun");
            var status = page.locator("#cnfStatus");
            page.locator("#cnfVariableCount").fill("62");
            page.locator("#cnfClauses").fill("1 -2");
            page.locator("#cnfFixed").fill("1");
            run.press("Enter");
            assertThat(status).containsText("Exakt 2305843009213693952 Belegungen");
            var file = page.waitForDownload(() -> page.locator("#cnfDownload").click());
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Files.readAllBytes(file.path()));
            assertEquals("2305843009213693952", json.at("/certificate/elimination/total").textValue());
            page.locator("#cnfWork").fill("1"); run.press("Enter");
            assertThat(status).containsText("Arbeitsbudget erschöpft");
            assertThat(page.locator("#cnfDownload")).isHidden();
            page.locator("#cnfClauses").fill("oops"); run.press("Enter");
            assertThat(status).containsText("ganze Zahlen");
            page.locator("#cnfClauses").fill("63"); run.press("Enter");
            assertThat(status).containsText("zwischen -62 und 62");
            page.reload();
            assertThat(page.locator("#cnfClauses")).hasValue("63");
            assertThat(page.locator("#cnfDownload")).isHidden();
            assertEquals(true, page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"));
        }
    }

    @Test void canRetryCancellationWhenTheFirstRequestArrivesBeforeAdmission() {
        try (var context = browser.newContext()) {
            var page = context.newPage();
            page.setDefaultTimeout(5_000);
            page.navigate(app.baseUrl() + "/static/certificate-demos.html");
            var heldCount = new AtomicReference<Route>();
            page.route("**/api/certificate-demos/cnf/*", heldCount::set);
            page.locator("#cnfRun").press("Enter");
            page.waitForCondition(() -> heldCount.get() != null);
            var cancel = page.locator("#cnfCancel");
            var early = page.waitForResponse(r -> r.url().endsWith("/cancel"), cancel::click);
            assertEquals(404, early.status());
            assertThat(cancel).isEnabled();
            var retry = page.waitForResponse(r -> r.url().endsWith("/cancel"), cancel::click);
            assertEquals(404, retry.status());
            heldCount.get().resume();
            assertThat(page.locator("#cnfStatus")).containsText("Exakt 5 Belegungen");
            assertThat(cancel).isHidden();
        }
    }

    @Test void countsEditedCnfCanBeCancelledOnTheServer() throws IOException {
        try (var context = browser.newContext()) {
            var page = context.newPage();
            page.setDefaultTimeout(5_000);
            page.navigate(app.baseUrl() + "/static/certificate-demos.html");
            page.locator("#cnfVariableCount").fill("256");
            var clauses = new StringBuilder();
            for (int i = 0; i < 128; i++)
                clauses.append(i + 1).append(' ').append(i + 129).append(' ').append((i * 13 + 7) % 128 + 1).append('\n');
            page.locator("#cnfClauses").fill(clauses.toString());
            page.locator("#cnfWork").fill("2000000");
            var response = page.waitForResponse(r -> r.url().endsWith("/cancel"), () -> page.evaluate("""
                () => {
                    document.querySelector('#cnfRun').click();
                    document.querySelector('#cnfCancel').click();
                }
                """));
            assertEquals(202, response.status());
            assertThat(page.locator("#cnfStatus")).containsText("Berechnung abgebrochen");
            assertThat(page.locator("#cnfDownload")).isHidden();
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

    @Test
    void aSlowHallResultPreservesFocusOnAnotherDemo() {
        try (var context = browser.newContext()) {
            var page = context.newPage();
            page.setDefaultTimeout(5_000);
            page.navigate(app.baseUrl() + "/static/certificate-demos.html");
            var pending = new AtomicReference<Route>();
            page.route("**/api/certificate-demos/hall", pending::set);

            var hall = page.locator("#hallRun");
            var montgomery = page.locator("#montgomeryRun");
            assertThat(hall).isEnabled();
            hall.press("Enter");
            assertThat(hall).isDisabled();
            page.keyboard().press("Tab");
            assertThat(montgomery).isFocused();
            var request = pending.get();
            assertNotNull(request);
            request.fulfill(new Route.FulfillOptions().setResponse(request.fetch()));

            assertThat(page.locator("#hallResult")).isVisible();
            assertThat(montgomery).isFocused();
        }
    }
}
