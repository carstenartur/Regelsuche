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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real immutable HTTP repository and browser; no current global search data. */
class RetainedRunWorkbenchBrowserTest {
    @TempDir Path temporary;
    private WebWorkbenchServer server;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private static final String TEST_REVISION = "0123456789abcdef0123456789abcdef01234567";
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

    @ParameterizedTest
    @ValueSource(strings = {"/", "/static/index.html"})
    void retainsImportsDeepLinksSelectionsAndOriginalBytes(String entryPoint) throws Exception {
        List<String> consoleErrors = new ArrayList<>();
        page.onConsoleMessage(message -> {
            if (message.type().equals("error") && !message.text().contains("favicon.ico")) {
                consoleErrors.add(message.text());
            }
        });
        page.navigate(url(entryPoint));
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
        assertTrue(consoleErrors.isEmpty(), consoleErrors.toString());
    }

    @Test void realRetentionFailureIsVisibleAfterReloadWithoutAPublishedWorkspace() throws Exception {
        Files.writeString(temporary.resolve("runs-dossiers"), "deliberately unavailable test repository");
        page.navigate(url("/static/index.html"));
        page.locator("[data-tab='runs']").click();
        page.locator("#startNativeSearch").click();
        page.waitForSelector("#nativeSearchStatus[data-state='FAILED']");
        assertTrue(page.locator("#nativeSearchStatus").textContent().contains("FileAlreadyExistsException"));
        page.reload();
        page.waitForSelector("#nativeSearchStatus[data-state='FAILED']");
        assertTrue(page.locator("#openNativeResult").isHidden());
    }

    @Test void canStopAPausedNativeOperationAndReopenItsTerminalReceipt() {
        page.navigate(url("/static/index.html"));
        page.locator("[data-tab='runs']").click();
        page.locator("#nativeStartPaused").check();
        page.locator("#startNativeSearch").click();
        page.waitForSelector("#nativeSearchStatus[data-state='PAUSED']");
        String operationUrl = page.url();
        page.locator("#stopNativeSearch").click();
        page.waitForSelector("#nativeSearchStatus[data-state='CANCELLED']");
        assertTrue(page.locator("#nativeSearchStatus").textContent().contains("OPERATOR_STOP"));
        page.navigate(operationUrl);
        page.waitForSelector("#nativeSearchStatus[data-state='CANCELLED']");
        assertTrue(page.locator("#openNativeResult").isHidden());
    }

    @Test void startsAndReopensAnActualRetainedNativeSearchAtNarrowWidth() throws Exception {
        page.setViewportSize(390, 844);
        page.navigate("http://127.0.0.1:" + server.boundPort() + "/#runs");
        page.locator("[data-tab='runs']").click();
        assertEquals(1,page.locator("#nativeSearchSource").count());
        page.locator("#nativeSearchSource").fill("x + 0");
        page.locator("#nativeStartPaused").check();
        page.locator("#startNativeSearch").focus();
        page.keyboard().press("Enter");
        page.waitForSelector("#nativeSearchStatus[data-state='PAUSED']");
        page.locator("#pauseNativeSearch").click();
        page.waitForFunction("document.querySelector('#nativeSearchStatus')?.dataset.state === 'COMPLETED'");
        assertTrue(page.locator("#nativeSearchProgress").textContent().contains("Besucht"));
        var link = page.locator("#openNativeResult");
        link.click();
        page.waitForFunction("location.hash.includes('run=') && document.querySelector('#candidateDossierDetail') !== null");
        var candidate = page.locator("#candidateDossierDetail").getAttribute("data-candidate-id");
        page.locator("[data-review-role='RULE_RADAR']").focus();
        page.keyboard().press("Enter");
        page.waitForFunction("document.querySelector('#candidateOccurrenceReview')?.textContent.includes('REPRODUCED_EXACT_OCCURRENCE')");
        var review = page.locator("#candidateOccurrenceReview");
        review.getByText("Zusätzlichen Annahmekontext untersuchen").click();
        review.getByLabel("Zu prüfende Voraussetzung").fill("x != 0");
        for (var input : java.util.Map.of("UNKNOWN", "", "TRUE", "x != 0", "FALSE", "x = 0", "CONFLICT", "x != 0\nx = 0").entrySet()) {
            review.getByLabel("Prämissen").fill(input.getValue());
            review.getByText("Voraussetzung prüfen", new com.microsoft.playwright.Locator.GetByTextOptions().setExact(true)).click();
            page.waitForSelector("[data-review-guard='" + input.getKey() + "']");
        }
        page.locator("#workbenchToolGroups summary").click();
        page.locator("#workbenchToolChoice").selectOption("review/PROOF_OBLIGATIONS");
        page.waitForFunction("document.querySelector('#candidateOccurrenceReview')?.textContent.includes('Gleichheitsprüfung · CONFIRMED')");
        var receipt = review.locator("a[href*='/candidate-evidence/']");
        try (var client = HttpClient.newHttpClient()) {
            var reopened = client.send(HttpRequest.newBuilder(URI.create(url(receipt.getAttribute("href")))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, reopened.statusCode(), reopened.body());
            assertEquals(review.locator("pre").textContent(), reopened.body());
        }
        Path captures = Path.of("build/reports/retained-run-ui");
        Files.createDirectories(captures);
        review.screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions().setPath(captures.resolve("bound-proof-narrow.png")));
        assertEquals(candidate,page.locator("#candidateDossierDetail").getAttribute("data-candidate-id"));
        String exactUrl = page.url();
        page.reload();
        page.waitForSelector("#candidateOccurrenceReview [data-review-role='PROOF_OBLIGATIONS'][aria-pressed='true']");
        assertEquals(exactUrl,page.url());
        assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"),
            () -> String.valueOf(page.evaluate("[document.documentElement.scrollWidth, innerWidth, ...Array.from(document.querySelectorAll('body *')).filter(e => e.getBoundingClientRect().right > innerWidth + 1 || e.scrollWidth > e.clientWidth + 1 && getComputedStyle(e).overflowX === 'visible').slice(0,24).map(e => [e.tagName,e.id,e.className,Math.round(e.getBoundingClientRect().right),e.scrollWidth,e.clientWidth])]")));
    }

    @Test void reviewsTheFullExtendedGraphBudgetOnANarrowScreen() throws Exception {
        page.setViewportSize(390, 844);
        page.navigate(url("/#runs"));
        page.locator("[data-tab='runs']").click();
        page.locator("#nativeSearchSource").fill("(x+0)*(x+0)");
        page.locator("#nativeSearchProfile").selectOption("extended");
        page.locator("#startNativeSearch").click();
        page.waitForSelector("#nativeSearchStatus[data-state='COMPLETED']");
        assertTrue(page.locator("#nativeSearchProgress").textContent().contains("Besucht 32 von 32"));
        assertTrue(page.locator("#nativeSearchStatus").textContent().contains("VISITED_STATE_BUDGET"));
        page.locator("#openNativeResult").click();
        page.waitForSelector("#candidateDossierDetail");
        page.locator("[data-review-role='SEARCH_GRAPH']").click();
        var graph = page.locator("#candidateGraphEvidence");
        assertTrue(graph.isVisible());
        var id = page.locator("#candidateDossierDetail").getAttribute("data-candidate-id");
        page.locator("#nativeGraphReplay").focus();
        page.keyboard().press("Enter");
        page.waitForSelector("#nativeReplaySelection");
        assertEquals(id, page.locator("#candidateDossierDetail").getAttribute("data-candidate-id"));
        assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"));
    }

    @Test void keepsAnUnsupportedPolynomialProofOpenAndBoundToTheSelectedCandidate() throws Exception {
        page.navigate(url("/#runs"));
        page.locator("[data-tab='runs']").click();
        page.locator("#nativeSearchSource").fill("sin(x) + 0");
        page.locator("#startNativeSearch").click();
        page.waitForSelector("#nativeSearchStatus[data-state='COMPLETED']");
        page.locator("#openNativeResult").click();
        page.waitForSelector("#candidateDossierDetail");
        String candidate = page.locator("#candidateDossierDetail").getAttribute("data-candidate-id");
        page.locator("[data-review-role='PROOF_OBLIGATIONS']").focus();
        page.keyboard().press("Enter");
        var review = page.locator("#candidateOccurrenceReview");
        review.locator("[data-verification-status='OUTSIDE_BOUNDED_POLYNOMIAL_FRAGMENT']").waitFor();
        assertTrue(review.textContent().contains("Gleichheitsverpflichtung bleibt offen"));
        assertEquals(candidate, page.locator("#candidateDossierDetail").getAttribute("data-candidate-id"));
        var receiptLink = review.locator("a[href*='/candidate-evidence/']");
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(url(receiptLink.getAttribute("href")))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            var receipt = new com.fasterxml.jackson.databind.json.JsonMapper().readTree(response.body());
            assertEquals(candidate, receipt.path("candidateId").asText());
            assertTrue(receipt.path("obligation").isObject());
            assertFalse(receipt.has("verification"));
            assertFalse(receipt.has("solverResult"));
        }
        String boundUrl = page.url();
        page.reload();
        review.locator("[data-verification-status='OUTSIDE_BOUNDED_POLYNOMIAL_FRAGMENT']").waitFor();
        assertEquals(boundUrl, page.url());
        assertEquals(candidate, page.locator("#candidateDossierDetail").getAttribute("data-candidate-id"));
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

    @Test void distinguishesUnusedBudgetFromUnknownTotalWorkUntilTheRunEnds() throws Exception {
        for (var state : List.of(RepresentationDiscoveryRunOutcome.TerminalState.RUNNING,
                RepresentationDiscoveryRunOutcome.TerminalState.COMPLETED)) {
            var outcome = RepresentationDiscoveryRunOutcome.create(state, state.name(), Long.MAX_VALUE, 13,
                sha("work"), sha("runtime"));
            var run = RepresentationDiscoveryRunWorkspace.create(first.input(), first.plan(), outcome,
                RepresentationDiscoveryRunWorkspace.notProducedArtifacts(), revisions());
            retain(run);
            page.navigate(url("/static/index.html#run=" + digest(run)));
            ready(run);
            assertEquals("9223372036854775794", page.locator("#retainedRunDetail tr")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Unverbrauchtes Budget"))
                .locator("td").textContent());
            assertEquals(state == RepresentationDiscoveryRunOutcome.TerminalState.RUNNING ? "Unbekannt" : "13",
                page.locator("#retainedRunDetail tr")
                    .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Gesamtarbeit bis zum Abschluss"))
                    .locator("td").textContent());
        }
    }

    @Test void rejectsLateResponsesAndChecksImmutableDtoAndIntegerContracts() throws Exception {
        page.navigate(url("/static/index.html"));
        try (var script = getClass().getResourceAsStream("/run-workspace-state-controls.js")) {
            assertNotNull(script);
            page.addScriptTag(new Page.AddScriptTagOptions().setContent(new String(script.readAllBytes(), StandardCharsets.UTF_8)));
        }
        assertEquals("Retained run state, budget, identity and selection controls passed",
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
        return RepresentationDiscoveryRevisionEvidence.create(TEST_REVISION, "run-browser-test/v1");
    }
    private static String sha(String value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
