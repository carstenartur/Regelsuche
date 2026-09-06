package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.*;
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

/** Production browser/worker handoff, independent of the optional external runner transport. */
class AdmissibleLocalResultBrowserTest {
    private static RegelsucheAppEnvironment app;
    private static Playwright playwright;
    private static Browser browser;
    private BrowserContext context;
    private Page page;
    private final List<String> errors = new ArrayList<>();
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
        context = browser.newContext(); page = context.newPage(); page.onPageError(errors::add);
        page.navigate(app.baseUrl() + "/static/admissible-workbench.html");
    }
    @AfterEach void close() {
        try { assertTrue(errors.isEmpty(), errors.toString()); }
        finally { if (context != null) context.close(); }
    }
    private String fixture() throws IOException {
        try (var raw = getClass().getResourceAsStream("/admissible/ci-window-128.json.gz")) {
            if (raw == null) throw new IOException("missing retained CI fixture");
            try (var stream = new GZIPInputStream(raw)) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
        }
    }
    private void deliver(String text) {
        page.evaluate("text => window.dispatchEvent(new CustomEvent('admissible:local-result', {detail: text}))", text);
    }
    @Test void runnerHandoffAndFileReimportPreserveExploratoryScope() throws IOException {
        String original = fixture();
        deliver(original);
        page.waitForFunction("document.querySelector('#status').textContent.includes('2 Optimalitätszertifikate')");
        assertTrue(page.locator("#summary").innerText().contains("Neue explorative Aufgabe"));
        assertTrue(page.locator("#proofStatus").innerText().contains("Maximum 28"));
        String live = (String) page.evaluate("""
            text => {
                const old = JSON.parse(text);
                return JSON.stringify({schema: 'admissible-workbench/v2', scope: 'exploratory',
                    sourceManifestSha256: old.sourceManifestSha256, selectedPolicy: old.selectedPolicy, runs: old.runs}) + '\n';
            }
            """.replace("+ '\n'", "+ '\\n'"), original);
        Path file = temporary.resolve("live.json"); Files.writeString(file, live);
        page.locator("#clear").click(); page.locator("#bundle").setInputFiles(file);
        page.waitForFunction("document.querySelector('#status').textContent.includes('2 Optimalitätszertifikate')");
        assertTrue(page.locator("#summary").innerText().contains("Kein Trainings- oder zurückgehaltener Testfall"));
        page.locator("#example").click();
        page.waitForFunction("document.querySelector('#summary').textContent.startsWith('Lehrbeispiel:')");
    }
    @Test void untrustedLocalBytesNeverBypassTheWorker() throws IOException {
        deliver(fixture());
        page.waitForFunction("document.querySelector('#status').textContent.includes('2 Optimalitätszertifikate')");
        deliver(fixture().replaceFirst("admissible-cardinality/v1", "wrong-proof/v1"));
        page.waitForFunction("!document.querySelector('#error').hidden");
        assertTrue(page.locator("#result").isHidden());
        page.evaluate("window.dispatchEvent(new CustomEvent('admissible:local-result', {detail: {ok: true}}))");
        assertTrue(page.locator("#error").innerText().contains("Ungültiges"));
        deliver(" ".repeat(8_000_001));
        assertTrue(page.locator("#error").innerText().contains("zu großes"));
    }
}
