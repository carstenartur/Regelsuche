package de.regelsuche.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real browser, exact backend, downloaded artifact and independently rerun import. */
class MatrixRepresentationBrowserTest {
    private static RegelsucheAppEnvironment app;
    private static Playwright playwright;
    private static Browser browser;
    private BrowserContext context;
    private Page page;
    private final List<String> errors = new ArrayList<>();
    @TempDir Path temporary;

    @BeforeAll static void boot() throws Exception {
        app = new RegelsucheAppEnvironment();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll static void stop() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (app != null) app.close();
    }

    @BeforeEach void open() {
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1360, 1000));
        page = context.newPage();
        page.onPageError(errors::add);
        page.navigate(app.baseUrl() + "/static/representations.html");
    }

    @AfterEach void close() {
        try { assertTrue(errors.isEmpty(), errors.toString()); }
        finally { context.close(); }
    }

    private void analyze() {
        page.locator("#representationAnalyze").click();
        page.waitForFunction("!document.querySelector('#representationAnalyze').disabled");
    }

    @Test void navigatesSourceAndMatrixThenExportsAndReplaysAfterReload() throws Exception {
        analyze();
        var card = page.locator("[data-origin='REPEATED_SOURCE_LINEAR_FORMS']");
        assertEquals(1, card.count());
        assertTrue(card.textContent().contains("Dieselbe lineare Abbildung"));
        assertEquals(2, card.locator(".representation-matrix table").count());
        card.getByText("Skalare Zeilen und Replay", new com.microsoft.playwright.Locator.GetByTextOptions().setExact(true)).click();
        assertTrue(card.locator(".representation-row-panel").isVisible());
        assertTrue(card.locator(".representation-row-panel").textContent().contains("5*x - 3*y = 6"));
        card.getByText("Matrixdarstellung", new com.microsoft.playwright.Locator.GetByTextOptions().setExact(true)).click();
        Path screenshot = Path.of("build/reports/matrix-preparation-ui/source-product.png");
        Files.createDirectories(screenshot.getParent());
        card.screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions().setPath(screenshot));
        var download = page.waitForDownload(() -> page.locator("#representationExport").click());
        Path artifact = temporary.resolve("representation.json"); download.saveAs(artifact);
        page.reload();
        page.locator("#representationImport").setInputFiles(artifact);
        page.waitForFunction("document.querySelector('#representationStatus').textContent.includes('Gesamter Replay bestätigt')");
        assertEquals(1, page.locator("[data-origin='REPEATED_SOURCE_LINEAR_FORMS']").count());
        Path tampered = temporary.resolve("tampered.json");
        Files.writeString(tampered, Files.readString(artifact).replace("\"sourceIndex\": 0", "\"sourceIndex\": 1"));
        page.locator("#representationImport").setInputFiles(tampered);
        page.waitForFunction("document.querySelector('#representationStatus').textContent.includes('Prüfung fehlgeschlagen')");
    }

    @Test void distinguishesEigenRecognitionAssumptionsAndDownstreamSolving() {
        page.locator("#representationExample").selectOption("eigen"); analyze();
        assertTrue(page.locator(".representation-eigen").textContent().contains("vector != 0"));
        assertTrue(page.locator(".representation-solving").textContent().contains("Charakteristisches Polynom"));
        page.locator("#representationNonzero").uncheck(); analyze();
        assertTrue(page.locator(".representation-eigen").textContent().contains("ASSUMPTION_REQUIRED"));
        assertFalse(page.locator(".representation-solving").textContent().contains("Charakteristisches Polynom"));
    }

    @Test void supportsMatrixInputAndReportsExhaustionOnANarrowViewport() {
        page.setViewportSize(390, 844);
        page.locator("#representationExample").selectOption("matrix"); analyze();
        assertEquals(1, page.locator("[data-origin='DECLARED_MATRIX_EQUATION']").count());
        assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"));
        page.locator("#representationBudget").fill("0"); analyze();
        assertTrue(page.locator("#representationStatus").textContent().contains("bleibt unentschieden"));
        assertEquals(0, page.locator(".representation-alternative").count());
    }
}
