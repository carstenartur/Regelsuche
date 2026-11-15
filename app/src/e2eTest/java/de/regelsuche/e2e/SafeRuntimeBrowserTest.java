package de.regelsuche.e2e;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import de.regelsuche.json.JsonReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real product controls, backend authority, downloaded evidence and fresh import. */
class SafeRuntimeBrowserTest {
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
        page.navigate(app.baseUrl());
    }
    @AfterEach void close() {
        try { assertTrue(errors.isEmpty(), errors.toString()); }
        finally { context.close(); }
    }

    @Test void explicitProfilesKeepGuardsVisibleAndExportReplaysAfterReload() throws Exception {
        assertEquals("", page.locator("#runtimeProfile").inputValue());
        select("SAFE_PREPARATION_V4", "4*x^2-y^2", "ast_square_difference_factor");
        submit();
        assertTrue(page.locator("#runtimeCandidates").textContent().contains("PREPARED"));
        assertTrue(page.locator("#runtimeSummary").textContent().contains("SAFE_PREPARATION_V4"));
        var download = page.waitForDownload(() -> page.locator("#runtimeExport").click());
        Path artifact = temporary.resolve("runtime.json"); download.saveAs(artifact);
        page.reload();
        page.locator("#runtimeImport").setInputFiles(artifact);
        page.waitForFunction("document.querySelector('#runtimeSummary').textContent.includes('Gesamter Replay bestätigt')");
        assertTrue(page.locator("#runtimeCandidates").textContent().contains("PREPARED"));
        Path forged = temporary.resolve("forged.json");
        Files.writeString(forged, Files.readString(artifact).replace("SAFE_PREPARATION_V4", "DIRECT_V1"));
        page.locator("#runtimeImport").setInputFiles(forged);
        page.waitForFunction("document.querySelector('#searchStatus').textContent.includes('Prüfung fehlgeschlagen')");
        assertTrue(page.locator("#runtimeExport").isDisabled());
        for (String profile : List.of("DIRECT_V1", "SAFE_PREPARATION_V4")) {
            select(profile, "1+(a/b)*(c/d)", "rational_multiply_fractions");
            var assumptions = openAssumptionText();
            assumptions.fill("b != 0\nd != 0");
            submit();
            assertTrue(page.locator("#runtimeCandidates").textContent().contains("Annahmen: b != 0, d != 0"));
            assumptions.fill("b != 0");
            submit();
            assertTrue(page.locator("#runtimeSummary").textContent().contains("UNSUPPORTED"));
            assertEquals("", page.locator("#runtimeCandidates").textContent());
        }
    }

    @Test void structuredAssumptionsAndDomainsRoundTripThroughTheRuntimeRequest() {
        page.setViewportSize(390, 844);
        var add = page.locator("#addRuntimeAssumption");
        assertFalse(add.isVisible());
        select("DIRECT_V1", "1+(a/b)*(c/d)", "rational_multiply_fractions");
        var rows = page.locator("#runtimeAssumptionRows li");
        for (String symbol : List.of("b", "d", "z")) {
            add.press("Enter");
            var input = rows.last().getByLabel("Ausdruck", new Locator.GetByLabelOptions().setExact(true));
            assertEquals(Boolean.TRUE, input.evaluate("element => document.activeElement === element"));
            input.fill(symbol);
            rows.last().getByLabel("Bedingung oder Zahlenbereich").selectOption(symbol.equals("z") ? " ∈ Z" : " != 0");
        }
        add.press("Enter");
        rows.last().getByLabel("Ausdruck", new Locator.GetByLabelOptions().setExact(true)).fill("custom(x, y)");
        assertEquals(Boolean.TRUE, page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"));

        String structured = page.waitForRequest("**/api/search", this::submit).postData();
        var payload = new JsonReader(structured).readObject();
        assertEquals(List.of("b != 0", "d != 0", "z ∈ Z", "custom(x, y)"),
            ((Map<?, ?>) payload.get("runtimeRequest")).get("assumptions"));
        assertTrue(page.locator("#runtimeCandidates").textContent().contains("b != 0"));
        assertTrue(page.locator("#runtimeCandidates").textContent().contains("z ∈ Z"));

        var assumptions = openAssumptionText();
        assertEquals("b != 0\nd != 0\nz ∈ Z\ncustom(x, y)", assumptions.inputValue());
        assumptions.fill("  b != 0\n\nd != 0\nz ∈ Z\ncustom(x, y)  \n");
        assertEquals(structured, page.waitForRequest("**/api/search", this::submit).postData());
        assertEquals(4, rows.count());
        assertEquals(" ∈ Z", rows.nth(2).getByLabel("Bedingung oder Zahlenbereich").inputValue());
        assertEquals("custom(x, y)  ", rows.last().getByLabel("Ausdruck", new Locator.GetByLabelOptions().setExact(true)).inputValue());

        rows.nth(1).getByRole(AriaRole.BUTTON).press("Enter");
        assertEquals(Boolean.TRUE, rows.nth(1).getByLabel("Ausdruck", new Locator.GetByLabelOptions().setExact(true))
            .evaluate("element => document.activeElement === element"));
        assertFalse(assumptions.inputValue().contains("d != 0"));
        rows.nth(1).getByLabel("Bedingung oder Zahlenbereich").selectOption(" ∈ Q");
        assertTrue(assumptions.inputValue().contains("z ∈ Q"));
        submit();
        assertTrue(page.locator("#runtimeSummary").textContent().contains("UNSUPPORTED"));
    }

    @Test void systemRemainsTypedThroughTheSearchControls() {
        select("SAFE_PREPARATION_V4", "x+(2*x+y)=4; y+(x+3*y)=5", "");
        page.locator("[name=runtimeShape]").selectOption("SYSTEM");
        submit();
        String candidate = page.locator("#runtimeCandidates").textContent();
        assertTrue(candidate.contains("SOLUTION_SET_EQUIVALENCE"), candidate);
        assertTrue(candidate.contains("UNIQUE"), candidate);
        assertTrue(page.locator("#searchOutput").textContent().contains("COMPLETE_STAGED_VECTOR_REPLAY_VERIFIED"));
    }

    @Test void nestedPreparationShowsItsLocalGuardsAndRemainsAblatable() throws Exception {
        select("DIRECT_V1", "1+((a/b)+0)*(c/d)", "rational_multiply_fractions,ast_add_zero_right");
        page.locator("[name=runtimePreparationRuleIds]").fill("ast_add_zero_right");
        openAssumptionText().fill("b != 0\nd != 0");
        submit();
        assertFalse(page.locator("#runtimeCandidates").textContent().contains("PREPARED"));
        page.locator("#runtimeProfile").selectOption("SAFE_PREPARATION_V4");
        submit();
        String candidate = page.locator("#runtimeCandidates").textContent();
        assertTrue(candidate.contains("PREPARED"), candidate);
        assertTrue(candidate.contains("Annahmen: b != 0, d != 0"), candidate);
        var download = page.waitForDownload(() -> page.locator("#runtimeExport").click());
        Path artifact = temporary.resolve("nested-runtime.json"); download.saveAs(artifact);
        page.reload();
        page.locator("#runtimeImport").setInputFiles(artifact);
        page.waitForFunction("document.querySelector('#runtimeSummary').textContent.includes('Gesamter Replay bestätigt')");
        assertTrue(page.locator("#runtimeCandidates").textContent().contains("PREPARED"));
        select("SAFE_PREPARATION_V4", "1+((a/b)+0)*(c/d)", "rational_multiply_fractions,ast_add_zero_right");
        page.locator("[name=runtimePreparationRuleIds]").fill("ast_add_zero_right");
        openAssumptionText().fill("b != 0");
        submit();
        assertFalse(page.locator("#runtimeCandidates").textContent().contains("PREPARED"));
    }

    private Locator openAssumptionText() {
        var text = page.locator("[name=runtimeAssumptions]");
        if (!text.isVisible()) page.locator("#runtimeAssumptionText summary").click();
        return text;
    }

    private void select(String profile, String source, String rules) {
        page.locator("#runtimeProfile").selectOption(profile);
        page.locator("[name=expression]").fill(source);
        page.locator("[name=runtimeRuleIds]").fill(rules);
        page.locator("[name=runtimeSymPy]").uncheck();
    }
    private void submit() {
        page.locator("#searchForm button[type=submit]").click();
        page.waitForFunction("document.querySelector('#runtimeSummary').textContent.includes('Profil:') || document.querySelector('#searchStatus').textContent.includes('Fehler')");
        assertTrue(page.locator("#runtimeSummary").textContent().contains("Profil:"), page.locator("#searchStatus").textContent());
    }
}
