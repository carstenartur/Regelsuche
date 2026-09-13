package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import de.regelsuche.discovery.representation.*;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.web.WebSecurityConfig;
import de.regelsuche.web.WebWorkbenchServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real immutable HTTP repository and browser; no current global search data. */
class RetainedRunWorkbenchBrowserTest {
    @TempDir Path temporary;
    private WebWorkbenchServer server;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private String previousDirectory;
    private final List<String> errors = new ArrayList<>();
    private RepresentationDiscoveryRunWorkspace first;
    private RepresentationDiscoveryRunWorkspace second;

    @BeforeEach void start() throws Exception {
        previousDirectory = System.getProperty("regelsuche.discovery.runs.directory");
        System.setProperty("regelsuche.discovery.runs.directory", temporary.resolve("runs").toString());
        server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), WebSecurityConfig.none());
        server.start();
        first = RepresentationDiscoveryRunWorkspace.create(
            RepresentationDiscoveryRunInput.expression("sin(x)^2 + cos(x)^2", List.of("x is real")),
            plan(42), RepresentationDiscoveryRunOutcome.created(), RepresentationDiscoveryRunWorkspace.notProducedArtifacts(), revisions());
        second = RepresentationDiscoveryRunWorkspace.duplicateWithOnePlanChange(first, plan(Long.MAX_VALUE), revisions());
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1360, 1000));
        page = context.newPage();
        page.onPageError(errors::add);
    }

    @AfterEach void stop() {
        try { assertTrue(errors.isEmpty(), errors.toString()); }
        finally {
            if (context != null) context.close();
            if (browser != null) browser.close();
            if (playwright != null) playwright.close();
            if (server != null) server.stop();
            if (previousDirectory == null) System.clearProperty("regelsuche.discovery.runs.directory");
            else System.setProperty("regelsuche.discovery.runs.directory", previousDirectory);
        }
    }

    @Test void retainsImportsDeepLinksSelectionsAndOriginalBytes() throws Exception {
        page.navigate(url("/static/index.html"));
        page.locator("[data-tab='runs']").click();
        page.locator("#importRetainedRun").setInputFiles(new FilePayload("run.json", "application/json", first.toCanonicalJson().getBytes(StandardCharsets.UTF_8)));
        ready(first);
        assertTrue(page.locator("#retainedRunDetail").textContent().contains("Noch nicht deklariert"));
        assertTrue(page.locator("[data-tab='graph']").isDisabled());
        page.locator("[data-artifact-role='PATH_REPLAY'] button").click();
        assertTrue(page.url().contains("artifact=PATH_REPLAY"));
        page.reload();
        ready(first);
        assertEquals("true", page.locator("[data-artifact-role='PATH_REPLAY']").getAttribute("aria-current"));
        assertTrue(page.locator("[data-artifact-role='PATH_REPLAY']").textContent().contains("NOT_PRODUCED_FOR_THIS_RUN"));
        var download = page.waitForDownload(() -> page.locator("#downloadRetainedRun").click());
        Path saved = temporary.resolve("download.json"); download.saveAs(saved);
        assertEquals(first.toCanonicalJson(), Files.readString(saved));
        page.locator("#leaveRetainedRun").click();
        assertFalse(page.locator("[data-tab='graph']").isDisabled());
        assertEquals(0, page.locator("body.retained-run").count());
    }

    @Test void comparesExactLongSeedsAndRemainsUsableOnNarrowViewports() throws Exception {
        retain(first); retain(second);
        page.navigate(url("/static/index.html#run=" + digest(first)));
        ready(first);
        page.locator("[data-compare-digest='" + digest(second) + "']").click();
        page.waitForFunction("document.querySelector('#runComparisonDetail').textContent.includes('9223372036854775807')");
        assertTrue(page.locator("#runComparisonDetail").textContent().contains("plan.deterministicSeed"));
        assertEquals("sha256:" + digest(first), page.locator("#retainedRunId").textContent());
        Path evidence = Path.of("build/reports/retained-run-ui"); Files.createDirectories(evidence);
        page.screenshot(new Page.ScreenshotOptions().setPath(evidence.resolve("comparison-desktop.png")));
        page.setViewportSize(390, 844);
        assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"));
        page.screenshot(new Page.ScreenshotOptions().setPath(evidence.resolve("comparison-narrow.png")));
        var select = page.locator("[data-artifact-role='SEARCH_GRAPH'] button");
        select.focus(); page.keyboard().press("Enter");
        assertEquals("true", page.locator("[data-artifact-role='SEARCH_GRAPH']").getAttribute("aria-current"));
        assertTrue((Boolean) page.evaluate("document.activeElement.closest('[data-artifact-role]').dataset.artifactRole === 'SEARCH_GRAPH'"));
    }

    @Test void rejectsInvalidImportsAndMissingHistoricalRunsVisibly() throws Exception {
        page.navigate(url("/static/index.html#run=" + "f".repeat(64)));
        page.waitForFunction("document.querySelector('#retainedRunStatus').textContent.includes('RUN_NOT_FOUND')");
        assertTrue(page.locator("#downloadRetainedRun").isDisabled());
        assertEquals(0, page.locator(".run-artifact").count());
        page.locator("#importRetainedRun").setInputFiles(new FilePayload("invalid.json", "application/json", "{}".getBytes(StandardCharsets.UTF_8)));
        page.waitForFunction("document.querySelector('#retainedRunStatus').textContent.includes('INVALID_RUN_WORKSPACE')");
        assertEquals(0, page.locator(".run-artifact").count());
    }

    @Test void rejectsLateResponsesAndChecksImmutableDtoAndIntegerContracts() throws Exception {
        page.navigate(url("/static/index.html"));
        try (var script = getClass().getResourceAsStream("/run-workspace-state-controls.js")) {
            assertNotNull(script);
            page.addScriptTag(new Page.AddScriptTagOptions().setContent(new String(script.readAllBytes(), StandardCharsets.UTF_8)));
        }
        assertEquals("11 state, identity, role, race and integer controls passed",
            page.evaluate("raw => window.runWorkspaceStateControls(JSON.parse(raw))", first.toCanonicalJson()));
    }

    private void ready(RepresentationDiscoveryRunWorkspace run) {
        page.waitForFunction("id => document.querySelector('#retainedRunId').textContent === id && !document.querySelector('#downloadRetainedRun').disabled", run.runId());
    }
    private String url(String path) { return "http://127.0.0.1:" + server.boundPort() + path; }
    private static String digest(RepresentationDiscoveryRunWorkspace run) { return run.runId().substring(7); }
    private void retain(RepresentationDiscoveryRunWorkspace run) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(url("/api/discovery-runs")))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(run.toCanonicalJson())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());
        }
    }
    private static RepresentationDiscoveryRunPlan plan(long seed) {
        return RepresentationDiscoveryRunPlan.create(RepresentationDiscoveryInformationBoundary.Track.R2_CATALOG_BLIND_POST_HOC_BRIDGE,
            sha("boundary"), sha("inventory"), sha("packs"), sha("catalog"), "target-free-breadth-first/v1",
            "pareto-archive/v1", "representation-discovery/v1", sha("budget"), seed, List.of("internal:java25"));
    }
    private static RepresentationDiscoveryRevisionEvidence revisions() {
        return RepresentationDiscoveryRevisionEvidence.create("0123456789abcdef0123456789abcdef01234567", "run-browser-test/v1");
    }
    private static String sha(String value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
