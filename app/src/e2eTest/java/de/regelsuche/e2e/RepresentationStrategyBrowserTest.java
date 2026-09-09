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

class RepresentationStrategyBrowserTest {
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
        page = context.newPage(); page.onPageError(errors::add);
        page.navigate(app.baseUrl() + "/static/strategy-demo.html");
    }
    @AfterEach void close() {
        try { assertTrue(errors.isEmpty(), errors.toString()); } finally { context.close(); }
    }
    @Test void trainsTransfersExportsAndRejectsTamperingAfterReload() throws Exception {
        page.locator("#solve").click();
        page.waitForFunction("!document.querySelector('#solve').disabled");
        assertTrue(page.locator("#comparison").textContent().contains("BUDGET_INCONCLUSIVE"));
        assertTrue(page.locator("#comparison").textContent().contains("VERIFIED"));
        page.locator("#train").click();
        page.waitForFunction("!document.querySelector('#studyResults').hidden");
        assertEquals(6, page.locator("#profileRows tr").count());
        assertTrue(page.locator("#learningStatus").textContent().contains("Eingefrorene Auswahl"));
        var download = page.waitForDownload(() -> page.locator("#export").click());
        Path artifact = temporary.resolve("solution.json"); download.saveAs(artifact);
        page.reload(); page.locator("#import").setInputFiles(artifact);
        page.waitForFunction("document.querySelector('#status').textContent.includes('Gesamter Replay bestätigt')");
        Path tampered = temporary.resolve("changed.json");
        Files.writeString(tampered, Files.readString(artifact).replace("x0+y0+z0=21", "x0+y0+z0=22"));
        page.locator("#import").setInputFiles(tampered);
        page.waitForFunction("document.querySelector('#status').textContent.includes('Prüfung fehlgeschlagen')");
        assertTrue(page.locator("#export").isDisabled());
    }
    @Test void handlesAnExhaustedBudgetOnMobile() {
        page.setViewportSize(390, 844); page.locator("#budget").fill("0"); page.locator("#solve").click();
        page.waitForFunction("!document.querySelector('#solve').disabled");
        assertTrue(page.locator("#status").textContent().contains("Kein bestätigter Abschluss"));
        assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"));
    }
}
