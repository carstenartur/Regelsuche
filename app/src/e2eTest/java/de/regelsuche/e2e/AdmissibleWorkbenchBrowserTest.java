package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real HTTP, worker and browser file import; no mocked solver or worker. */
class AdmissibleWorkbenchBrowserTest {
    private static RegelsucheAppEnvironment app;
    private static Playwright playwright;
    private static Browser browser;
    private BrowserContext context;
    private Page page;
    private final List<String> errors = new ArrayList<>();
    private final List<String> writes = new ArrayList<>();
    @TempDir Path temporary;

    @BeforeAll static void boot() throws IOException {
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
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1440, 1050));
        page = context.newPage();
        page.onPageError(errors::add);
        page.onRequest(request -> { if (!request.method().equals("GET")) writes.add(request.url()); });
        page.navigate(app.baseUrl() + "/static/admissible-workbench.html");
    }
    @AfterEach void close() {
        try {
            assertTrue(errors.isEmpty(), errors.toString());
            assertTrue(writes.isEmpty(), "Local import must not upload files: " + writes);
        } finally { if (context != null) context.close(); }
    }
    private void example() {
        page.locator("#example").click();
        page.waitForFunction("document.querySelector('#status').textContent.includes('1 Optimalitätszertifikate')");
    }
    private Path fixture() throws IOException {
        try (var raw = getClass().getResourceAsStream("/admissible/ci-window-128.json.gz")) {
            if (raw == null) throw new IOException("missing retained CI fixture");
            try (var input = new GZIPInputStream(raw)) {
                Path path = temporary.resolve("workbench.json");
                Files.write(path, input.readAllBytes());
                return path;
            }
        }
    }
    private void importFixture() throws IOException {
        page.locator("#bundle").setInputFiles(fixture());
        page.waitForFunction("document.querySelector('#status').textContent.includes('2 Optimalitätszertifikate')");
    }

    @Test void opensFromMainWorkbenchWithoutDiscardingExpression() {
        page.navigate(app.baseUrl());
        page.locator("input[name=expression]").fill("x + 7");
        Page experiment = page.waitForPopup(() -> page.locator("#openAdmissibleWorkbench").click());
        try {
            experiment.waitForLoadState();
            experiment.onPageError(errors::add);
            assertTrue(experiment.url().endsWith("/static/admissible-workbench.html"));
            assertEquals(Boolean.TRUE, experiment.evaluate("window.opener === null"));
            experiment.locator("#example").click();
            experiment.waitForFunction("document.querySelector('#status').textContent.includes('1 Optimalitätszertifikate')");
            assertEquals("x + 7", page.locator("input[name=expression]").inputValue());
        } finally { experiment.close(); }
    }

    @Test void exploreAllSmallAlternativesAndReturn() {
        example();
        assertEquals(9, page.locator("#offsets span").count());
        page.locator("#branches button").click();
        assertEquals(5, page.locator("#offsets span").count());
        assertEquals(2, page.locator("#branches button").count());
        page.locator("#branches button").first().focus();
        page.keyboard().press("Enter");
        assertTrue(page.locator("#explanation").innerText().contains("nicht verbessern"));
        page.locator("#back").click();
        page.locator("#branches button").last().click();
        assertEquals(3, page.locator("#offsets span").count());
        page.locator("#root").click();
        assertEquals(9, page.locator("#offsets span").count());
    }
    @Test void importsRealCiProofsAndSwitchesStrategy() throws IOException {
        importFixture();
        assertEquals(2, page.locator("#comparisons tr").count());
        page.locator("#run").selectOption("1");
        assertTrue(page.locator("#proofStatus").innerText().contains("Maximum 28"));
        assertTrue(page.locator("#scope").innerText().contains("importierte Angaben"));
        page.locator("#tamper").click();
        assertTrue(page.locator("#tamperStatus").innerText().contains("abgewiesen"));
        assertEquals(2, page.locator("#comparisons tr").count());
        Path screenshot = Path.of("build/reports/admissible-workbench/desktop.png");
        Files.createDirectories(screenshot.getParent());
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshot).setFullPage(true));
    }
    @Test void rejectsCorruptionAndDropsPreviousGoodResult() throws IOException {
        example();
        Path file = fixture();
        String text = Files.readString(file, StandardCharsets.UTF_8);
        text = text.replaceFirst("admissible-cardinality/v1", "wrong-proof/v1");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        page.locator("#bundle").setInputFiles(file);
        page.waitForFunction("!document.querySelector('#error').hidden");
        assertTrue(page.locator("#result").isHidden());
        example();
        assertTrue(page.locator("#result").isVisible());
    }
    @Test void delayedFileReadCannotOverwriteNewerExample() throws IOException {
        page.evaluate("""
            () => {
                const original = File.prototype.text;
                File.prototype.text = function() {
                    const file = this;
                    return new Promise(resolve => {
                        window.releaseOldRead = async () => resolve(await original.call(file));
                    });
                };
            }
            """);
        page.locator("#bundle").setInputFiles(fixture());
        example();
        page.evaluate("window.releaseOldRead()");
        assertEquals(1, page.locator("#comparisons tr").count());
        page.locator("#clear").click();
        assertTrue(page.locator("#result").isHidden());
        assertTrue(page.locator("#status").innerText().contains("Zurückgesetzt"));
    }
    @Test void mobileLayoutAndKeyboardRemainUsable() throws IOException {
        page.setViewportSize(390, 844);
        example();
        assertEquals(Boolean.TRUE, page.evaluate("document.documentElement.scrollWidth <= innerWidth"));
        page.locator("#branches button").focus();
        page.keyboard().press("Enter");
        assertEquals(2, page.locator("#branches button").count());
        Path screenshot = Path.of("build/reports/admissible-workbench/mobile.png");
        Files.createDirectories(screenshot.getParent());
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshot).setFullPage(true));
    }
    @Test void rejectsOversizeBeforeReading() throws IOException {
        Path large = temporary.resolve("large.json");
        Files.writeString(large, " ".repeat(8_000_001));
        page.locator("#bundle").setInputFiles(large);
        assertTrue(page.locator("#error").innerText().contains("8 MB"));
        assertFalse(page.locator("#result").isVisible());
    }
    @Test void independentProofGuardsRunInTheBrowser() {
        Object result = page.evaluate("""
            () => {
                const a = AdmissibleProof;
                const bad = [a.example.replace('1ff:2\\n', ''),
                    a.example.replace('155:3', '155:4'),
                    a.example.replace('155:3', '1:2\\n155:3'),
                    a.example.replace('[0, 2, 6, 8]', '[0, 2, 4, 6, 8]')];
                return bad.every(text => { try { a.parseProof(text); return false; } catch (_) { return true; } });
            }
            """);
        assertEquals(Boolean.TRUE, result);
    }
}
