package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.FilePayload;
import de.regelsuche.discovery.representation.TargetFreeRepresentationDiscoveryRun;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.web.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CandidateDossierBrowserTest {
    private static final TargetFreeRepresentationDiscoveryRun.RunBundle BUNDLE =
        TargetFreeRepresentationDiscoveryRun.run("0123456789abcdef0123456789abcdef01234567");
    @TempDir Path temporary;
    private WebWorkbenchServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;
    private String previousDirectory;
    private final List<String> errors = new ArrayList<>();

    @BeforeEach void start() throws Exception {
        previousDirectory = System.getProperty("regelsuche.discovery.runs.directory");
        System.setProperty("regelsuche.discovery.runs.directory", temporary.resolve("runs").toString());
        server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), WebSecurityConfig.none());
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(url("/api/discovery-runs")))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(BUNDLE.workspace().toCanonicalJson())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode());
        }
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1360, 1000));
        page.onPageError(errors::add);
        page.onConsoleMessage(message -> { if (message.type().equals("error") && !message.text().contains("favicon.ico")
            && !message.text().contains("404")) errors.add(message.text()); });
    }

    @AfterEach void stop() {
        try { assertTrue(errors.isEmpty(), errors.toString()); }
        finally {
            if (browser != null) browser.close();
            if (playwright != null) playwright.close();
            if (server != null) server.stop();
            if (previousDirectory == null) System.clearProperty("regelsuche.discovery.runs.directory");
            else System.setProperty("regelsuche.discovery.runs.directory", previousDirectory);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"/", "/index.html", "/static/index.html"})
    void reviewsExactCandidateAndGraphEvidenceAcrossReloadAndKeyboardSelection(String entry) throws Exception {
        page.navigate(url(entry + "#run=" + digest()));
        page.locator("#importCandidateDossier").setInputFiles(new FilePayload("scenario.json", "application/json",
            BUNDLE.scenario().toCanonicalJson().getBytes(StandardCharsets.UTF_8)));
        String bridgeId = BUNDLE.scenario().content().discoveredBridge().stateHash();
        page.waitForFunction("id => document.querySelector('#candidateDossierDetail')?.textContent.includes(id)", bridgeId);
        var detail = page.locator("#candidateDossierDetail");
        assertTrue(detail.textContent().contains("SYMBOLICALLY_VERIFIED"));
        assertTrue(detail.textContent().contains("rule:sympy.trig.pythagorean"));
        assertTrue(detail.textContent().contains("semanticValueOccurrences"));
        assertTrue(detail.textContent().contains("UNKNOWN"));
        assertTrue(detail.textContent().contains("FALSE"));
        assertTrue(detail.textContent().contains("Konflikt"));
        assertTrue(detail.textContent().contains("Nicht im Artefakt"));
        assertTrue(page.url().contains("candidate="));
        page.locator("[data-dossier-edge]").first().click();
        assertTrue(page.url().contains("edge="));
        assertTrue(page.url().contains("artifact=SEARCH_GRAPH"));
        assertTrue(page.locator("#candidateGraphEvidence").textContent().contains("applicationKey"));
        page.reload();
        page.waitForFunction("id => document.querySelector('#candidateDossierDetail')?.textContent.includes(id)", bridgeId);
        assertTrue(page.url().contains("edge="));
        var other = BUNDLE.scenario().content().search().candidateStates().stream()
            .filter(state -> !state.stateHash().equals(bridgeId)).findFirst().orElseThrow();
        var otherButton = page.locator("[data-candidate-id='" + other.stateHash() + "']");
        otherButton.focus(); page.keyboard().press("Enter");
        assertEquals(other.stateHash(), page.locator("#candidateDossierDetail").getAttribute("data-candidate-id"));
        assertFalse(page.locator("#candidateDossierDetail").textContent().contains("SYMBOLICALLY_VERIFIED"));
        assertTrue((Boolean) page.evaluate("document.activeElement.dataset.candidateId === '" + other.stateHash() + "'"));
        page.locator("[data-candidate-id='" + bridgeId + "']").first().click();
        if (entry.equals("/")) {
            Path evidence = Path.of("build/reports/candidate-dossier-ui"); Files.createDirectories(evidence);
            page.locator("#candidateDossierDetail").evaluate("element => element.scrollIntoView({block: 'start'})");
            page.screenshot(new Page.ScreenshotOptions().setPath(evidence.resolve("dossier-desktop.png")));
            page.setViewportSize(390, 844);
            page.locator("#candidateDossierDetail").evaluate("element => element.scrollIntoView({block: 'start'})");
            page.screenshot(new Page.ScreenshotOptions().setPath(evidence.resolve("dossier-narrow.png")));
            assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"),
                () -> String.valueOf(page.evaluate("({width: innerWidth, document: document.documentElement.scrollWidth, overflow: [...document.querySelectorAll('body *')].filter(e => e.scrollWidth > e.clientWidth + 1 && getComputedStyle(e).overflowX === 'visible').map(e => [e.tagName,e.id,e.className,e.scrollWidth,e.clientWidth,e.textContent.slice(0,40)])})")));
        }
    }

    @Test void duplicatesAVisibleSeedChangeAndComparesWithImmutableParent() {
        page.navigate(url("/#run=" + digest()));
        page.locator("#duplicateRunSeed").fill("9223372036854775807");
        page.locator("#duplicateRetainedRun button").click();
        page.waitForFunction("document.querySelector('#retainedRunStatus').textContent.includes('NOT_STARTED')");
        page.waitForFunction("document.querySelector('#runComparisonDetail').textContent.includes('plan.deterministicSeed')");
        assertTrue(page.locator("#retainedRunDetail").textContent().contains(BUNDLE.workspace().runId()));
        assertTrue(page.locator("#runComparisonDetail").textContent().contains("plan.deterministicSeed"));
        assertTrue(page.locator("#runComparisonDetail").textContent().contains("9223372036854775807"));
        assertEquals(0, page.locator(".run-candidate-dossier [data-candidate-id]").count());
        String parentId = page.locator("#retainedRunId").textContent();
        page.locator("#duplicateRunSeed").fill("-0");
        page.locator("#duplicateRetainedRun button").click();
        page.waitForFunction("parent => (!document.querySelector('#downloadRetainedRun').disabled && document.querySelector('#retainedRunId').textContent !== parent) || document.querySelector('#duplicateRetainedRun')?.textContent.includes('fehlgeschlagen')", parentId);
        assertNotEquals(parentId, page.locator("#retainedRunId").textContent(), "an exact zero seed change must accept canonical normalization");
    }

    @ParameterizedTest @ValueSource(strings = {"/", "/index.html", "/static/index.html"})
    void preservesRoleOnlyDeepLinksWhenDefaultCandidateLoads(String entry) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(url("/api/discovery-runs/" + digest() + "/dossier")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BUNDLE.scenario().toCanonicalJson())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());
        }
        String target = entry + "#run=" + digest() + "&artifact=PATH_REPLAY";
        page.navigate(url(target));
        page.waitForSelector("#candidateDossierDetail");
        assertEquals("PATH_REPLAY", page.evaluate("new URLSearchParams(location.hash.slice(1)).get('artifact')"));
        assertEquals("true", page.locator("[data-artifact-role='PATH_REPLAY']").getAttribute("aria-current"));
        page.reload();
        page.waitForSelector("#candidateDossierDetail");
        assertEquals("PATH_REPLAY", page.evaluate("new URLSearchParams(location.hash.slice(1)).get('artifact')"));
        var other = BUNDLE.scenario().content().search().candidateStates().stream()
            .filter(state -> !state.stateHash().equals(BUNDLE.scenario().content().discoveredBridge().stateHash())).findFirst().orElseThrow();
        page.navigate(url(target + "&candidate=" + other.stateHash()));
        page.waitForFunction("id => document.querySelector('#candidateDossierDetail')?.dataset.candidateId === id", other.stateHash());
        assertEquals("PATH_REPLAY", page.evaluate("new URLSearchParams(location.hash.slice(1)).get('artifact')"));
    }

    @Test void rejectsForeignCandidateEvidenceAndLateResponses() throws Exception {
        page.navigate(url("/"));
        try (var script = getClass().getResourceAsStream("/candidate-dossier-state-controls.js")) {
            assertNotNull(script);
            page.addScriptTag(new Page.AddScriptTagOptions().setContent(new String(script.readAllBytes(), StandardCharsets.UTF_8)));
        }
        assertEquals("candidate correlation and stale-response controls passed", page.evaluate(
            "input => window.candidateDossierStateControls(JSON.parse(input.run), JSON.parse(input.artifact))",
            Map.of("run", BUNDLE.workspace().toCanonicalJson(), "artifact", BUNDLE.scenario().toCanonicalJson())));
    }

    @Test void aLateDuplicationResponseCannotReplaceNewerArtifactSelection() {
        page.navigate(url("/#run=" + digest()));
        page.locator("#duplicateRunSeed").fill("9");
        page.evaluate("""
            () => {
                const original = window.fetch;
                window.fetch = async (...args) => {
                    const response = await original(...args);
                    if (String(args[0]).endsWith('/duplicate')) {
                        const read = response.text.bind(response);
                        response.text = async () => {
                            const raw = await read();
                            queueMicrotask(() => { window.duplicateResponseRead = true; });
                            return raw;
                        };
                    }
                    return response;
                };
            }
            """);
        var pending = new java.util.concurrent.atomic.AtomicReference<Route>();
        page.route("**/duplicate", pending::set);
        page.locator("#duplicateRetainedRun button").click();
        page.waitForCondition(() -> pending.get() != null);
        page.locator("[data-artifact-role='PROOF_OBLIGATIONS'] button").click();
        var response = pending.get().fetch();
        assertEquals(201, response.status());
        pending.get().fulfill(new Route.FulfillOptions().setResponse(response));
        page.waitForFunction("window.duplicateResponseRead === true");
        assertEquals(BUNDLE.workspace().runId(), page.locator("#retainedRunId").textContent());
        assertTrue(page.url().contains("artifact=PROOF_OBLIGATIONS"));
    }

    private static String digest() { return BUNDLE.workspace().runId().substring(7); }
    private String url(String path) { return "http://127.0.0.1:" + server.boundPort() + path; }
}
