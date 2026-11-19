package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.RequestOptions;
import de.regelsuche.discovery.domain.*;
import de.regelsuche.discovery.domain.DiscoveryDomain.*;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.web.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real retained Java exports and proof requests; routes only delay or corrupt actual responses. */
class DomainModelProofBrowserTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;
    private WebWorkbenchServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;
    private String previous;
    private DomainExportWorkspaceRepository repository;
    private final List<String> errors = new ArrayList<>();

    @BeforeEach void start() throws Exception {
        previous = System.getProperty("regelsuche.discovery.runs.directory");
        System.setProperty("regelsuche.discovery.runs.directory", temporary.resolve("runs").toString());
        repository = new DomainExportWorkspaceRepository(temporary.resolve("runs-domain-exports"));
        server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), WebSecurityConfig.none());
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1300, 900));
        page.onPageError(errors::add);
    }

    @AfterEach void stop() {
        try { assertTrue(errors.isEmpty(), errors.toString()); }
        finally {
            if (browser != null) browser.close();
            if (playwright != null) playwright.close();
            if (server != null) server.stop();
            if (previous == null) System.clearProperty("regelsuche.discovery.runs.directory");
            else System.setProperty("regelsuche.discovery.runs.directory", previous);
        }
    }

    @Test void provesTheDisplayedSquareModelWithKeyboardAndRetainsAnExactReceipt() throws Exception {
        String runId = retain(false, "observed=1,4,9,16;holdout=25,36");
        open(runId);
        var button = proofButton();
        button.focus();
        page.keyboard().press("Enter");
        waitForProof("PROVED_MODEL");
        assertTrue(page.locator("#retainedDomainModelProofStatus").textContent().contains("Modell"));
        assertTrue(page.locator("#domainModelProofScope").textContent().contains("keinen Beweis"));
        assertTrue(page.locator("#domainModelProofScope").textContent().contains("unendlichen"));
        assertTrue(page.locator("#domainModelProofModel").textContent().contains("x0(0) = 1"));
        assertTrue(page.locator("#domainModelProofModel").textContent().contains("x1(0) = 3"));
        assertTrue(page.locator("#domainModelProofRecurrence").textContent().contains("a(n +"));
        assertEquals("10201", page.locator("[data-model-index='100'] [data-model-value]").textContent());
        assertTrue(page.locator("[data-model-index='100']").textContent().contains("Modellvorhersage"));
        assertTrue(page.locator("#domainModelProofFiniteCheck").textContent().contains("endlichen Daten"));
        for (String phase : List.of("replay", "finite-check", "preparation", "binding", "queries")) {
            assertEquals(1, page.locator("#domainModelProofWork [data-proof-phase='" + phase + "']").count());
        }
        assertTrue(page.locator("#domainModelProofWork").textContent().contains("Keine Gesamtarbeit"));
        assertFalse(page.locator("#domainModelProofIdentity").evaluate("node => node.open").equals(Boolean.TRUE));

        var link = page.locator("#downloadDomainModelProofReceipt");
        String receiptPath = link.getAttribute("href");
        assertTrue(receiptPath.matches("/api/discovery-domains/exports/" + runId.substring(7) + "/model-proof/[0-9a-f]{64}"));
        var download = page.waitForDownload(link::click);
        byte[] receipt = Files.readAllBytes(download.path());
        assertArrayEquals(page.request().get(url(receiptPath)).body(), receipt);
        var body = JSON.readTree(receipt);
        assertEquals("regelsuche.domain-model-proof/v1", body.path("schema").asText());
        assertEquals("PROVED_MODEL", body.path("status").asText());
        assertEquals("NOT_ESTABLISHED", body.path("originalSequenceProofStatus").asText());
        assertEquals(runId, body.at("/source/runId").asText());

        assertNarrowProofFits("squares-390px");
        page.locator("#domainModelProofWork > summary").focus();
        page.keyboard().press("Enter");
        assertEquals(Boolean.TRUE, page.locator("#domainModelProofWork").evaluate("node => node.open"));
        assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"));

        page.reload();
        waitFor(runId);
        assertNull(page.locator("#retainedDomainModelProofStatus").getAttribute("data-status"));
        assertNull(page.locator("#downloadDomainModelProofReceipt").getAttribute("href"));
        proofButton().click();
        waitForProof("PROVED_MODEL");
        assertEquals(receiptPath, page.locator("#downloadDomainModelProofReceipt").getAttribute("href"));
        assertTrue(page.locator("#retainedDomainExportDetail").textContent().contains("NOT_EVALUATED"));
    }

    @Test void preservesAsymmetricRecurrenceCoefficientOrderInTheModelAndExactPrediction() throws Exception {
        String runId = retain(true, "observed=1,4,11,26,57,120;holdout=247,502,1013;maximumOrder=3");
        open(runId);
        proofButton().click();
        waitForProof("PROVED_MODEL");
        assertTrue(page.locator("#domainModelProofModel").textContent()
            .contains("x2(n + 1) = 2 × x0(n) − 5 × x1(n) + 4 × x2(n)"));
        assertTrue(page.locator("#domainModelProofRecurrence").textContent()
            .contains("a(n + 3) = 2 × a(n) − 5 × a(n + 1) + 4 × a(n + 2)"));
        assertEquals(BigInteger.TWO.pow(102).subtract(BigInteger.valueOf(103)).toString(),
            page.locator("[data-model-index='100'] [data-model-value]").textContent());
        assertEquals("1013", page.locator("[data-model-index='8'] [data-model-value]").textContent());
        assertNarrowProofFits("asymmetric-recurrence-390px");
    }

    @Test void provesTheStandardRecurrenceAgainstIndependentExactIteration() throws Exception {
        String runId = retain(true, "observed=2,3,5,8,13,21;holdout=34,55,89;maximumOrder=3");
        open(runId);
        proofButton().click();
        waitForProof("PROVED_MODEL");
        BigInteger first = BigInteger.TWO, second = BigInteger.valueOf(3);
        for (int index = 0; index < 100; index++) {
            BigInteger next = first.add(second);
            first = second;
            second = next;
        }
        assertEquals(first.toString(), page.locator("[data-model-index='100'] [data-model-value]").textContent());
        assertTrue(page.locator("#domainModelProofRecurrence").textContent()
            .contains("a(n + 2) = a(n) + a(n + 1)"));
    }

    @Test void explainsAnUnsupportedModelWithoutShowingProofOrPredictions() throws Exception {
        String runId = retain(false, "observed=1,16,81,256,625,1296;holdout=2401");
        open(runId);
        proofButton().click();
        waitForProof("UNSUPPORTED");
        assertTrue(page.locator("#retainedDomainModelProofStatus").textContent().contains("unterstützt"));
        assertTrue(page.locator("#domainModelProofFiniteCheck").textContent().contains("endlichen Daten"));
        assertTrue(page.locator("#domainModelProofFiniteCheck").textContent().contains("nicht unabhängig geprüft"));
        assertFalse(page.locator("#domainModelProofRecurrence").isVisible());
        assertEquals(0, page.locator("[data-model-index]").count());
        assertTrue(page.locator("#downloadDomainModelProofReceipt").isVisible());
    }

    @Test void aLateProofCannotReplaceTheCurrentlySelectedSourceOrReceipt() throws Exception {
        String firstId = retain(false, "observed=1,4,9,16;holdout=25,36");
        String secondId = retain(true, "observed=2,3,5,8,13,21;holdout=34,55,89;maximumOrder=3");
        open(firstId);
        String oldPath = "/api/discovery-domains/exports/" + firstId.substring(7) + "/model-proof";
        page.evaluate("""
            path => {
                const original = window.fetch;
                window.fetch = async (...args) => {
                    const response = await original(...args);
                    if (args[0] === path) {
                        const read = response.text.bind(response);
                        response.text = async () => { const text = await read(); window.oldModelProofRead = true; return text; };
                    }
                    return response;
                };
            }
            """, oldPath);
        var pending = new AtomicReference<Route>();
        page.route("**" + oldPath, pending::set);
        proofButton().click();
        page.waitForCondition(() -> pending.get() != null);
        page.locator("[data-domain-export='" + secondId + "']").click();
        waitFor(secondId);
        assertNull(page.locator("#downloadDomainModelProofReceipt").getAttribute("href"));
        assertEquals(0, page.locator("[data-model-index]").count());
        proofButton().click();
        waitForProof("PROVED_MODEL");
        String currentPath = page.locator("#downloadDomainModelProofReceipt").getAttribute("href");
        String currentValue = page.locator("[data-model-index='100'] [data-model-value]").textContent();
        var oldResponse = pending.get().fetch();
        assertEquals(200, oldResponse.status());
        pending.get().fulfill(new Route.FulfillOptions().setResponse(oldResponse));
        page.waitForFunction("window.oldModelProofRead === true");
        assertEquals(secondId, page.locator("#retainedDomainExportDetail").getAttribute("data-run-id"));
        assertEquals(currentPath, page.locator("#downloadDomainModelProofReceipt").getAttribute("href"));
        assertEquals(currentValue, page.locator("[data-model-index='100'] [data-model-value]").textContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "candidate", "header", "location", "etag", "scope"})
    void rejectsForeignProofBindingsAndDownloadIdentities(String damage) throws Exception {
        String runId = retain(false, "observed=1,4,9,16;holdout=25,36");
        open(runId);
        var button = proofButton();
        page.route("**/model-proof", route -> {
            var response = route.fetch();
            var headers = new HashMap<>(response.headers());
            try {
                var body = (ObjectNode) JSON.readTree(response.body());
                String foreignHash = "sha256:" + "0".repeat(64);
                switch (damage) {
                    case "source" -> ((ObjectNode) body.path("source")).put("workspaceHash", foreignHash);
                    case "candidate" -> ((ObjectNode) body.path("source")).put("selectedCandidateHash", foreignHash);
                    case "header" -> headers.put("x-regelsuche-run-id", foreignHash);
                    case "location" -> headers.put("location", "https://example.invalid/foreign-proof");
                    case "etag" -> headers.put("etag", "\"" + "0".repeat(64) + "\"");
                    case "scope" -> body.put("originalSequenceProofStatus", "PROVED");
                    default -> throw new AssertionError(damage);
                }
                route.fulfill(new Route.FulfillOptions().setResponse(response).setHeaders(headers).setBody(body.toString()));
            } catch (java.io.IOException exception) {
                throw new RuntimeException(exception);
            }
        });
        button.click();
        page.waitForFunction("document.querySelector('#retainedDomainModelProofStatus')?.textContent.startsWith('Modellprüfung fehlgeschlagen:')");
        assertNull(page.locator("#retainedDomainModelProofStatus").getAttribute("data-status"));
        assertNull(page.locator("#downloadDomainModelProofReceipt").getAttribute("href"));
        assertEquals(0, page.locator("[data-model-index]").count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"query", "foreign-model"})
    void refusesPostContentThatDiffersFromTheRetainedProof(String damage) throws Exception {
        String runId = retain(false, "observed=1,4,9,16;holdout=25,36");
        String foreignId = retain(true, "observed=2,3,5,8,13,21;holdout=34,55,89;maximumOrder=3");
        var foreign = retainedProof(foreignId);
        open(runId);
        page.route("**/model-proof", route -> {
            var response = route.fetch();
            try {
                var body = (ObjectNode) JSON.readTree(response.body());
                if (damage.equals("query")) {
                    for (var query : body.at("/proof/queries")) {
                        if (query.path("index").asInt() == 100) ((ObjectNode) query).put("value", "999");
                    }
                } else {
                    body.set("model", foreign.path("model"));
                    body.set("proof", foreign.path("proof"));
                }
                // Keep the genuine source, receipt hash, response headers and download location unchanged.
                route.fulfill(new Route.FulfillOptions().setResponse(response).setBody(body.toString()));
            } catch (java.io.IOException exception) {
                throw new RuntimeException(exception);
            }
        });
        proofButton().click();
        assertProofRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "bytes", "source-header", "etag"})
    void refusesFailedOrChangedRetainedReceiptResponses(String damage) throws Exception {
        String runId = retain(false, "observed=1,4,9,16;holdout=25,36");
        open(runId);
        page.route("**/model-proof/*", route -> {
            var response = route.fetch();
            var headers = new HashMap<>(response.headers());
            var changed = new Route.FulfillOptions().setResponse(response);
            switch (damage) {
                case "failed" -> changed.setStatus(500);
                // Identical parsed JSON still has different retained bytes and must be rejected.
                case "bytes" -> changed.setBody(response.text() + "\n");
                case "source-header" -> headers.put("x-regelsuche-run-id", "sha256:" + "0".repeat(64));
                case "etag" -> headers.put("etag", "\"" + "0".repeat(64) + "\"");
                default -> throw new AssertionError(damage);
            }
            route.fulfill(changed.setHeaders(headers));
        });
        proofButton().click();
        assertProofRejected();
    }

    @Test void aPendingRetainedReceiptCannotRenderAfterTheSourceChanges() throws Exception {
        String firstId = retain(false, "observed=1,4,9,16;holdout=25,36");
        String secondId = retain(true, "observed=2,3,5,8,13,21;holdout=34,55,89;maximumOrder=3");
        open(firstId);
        String receiptPrefix = "/api/discovery-domains/exports/" + firstId.substring(7) + "/model-proof/";
        page.evaluate("""
            prefix => {
                const original = window.fetch;
                window.fetch = async (...args) => {
                    const response = await original(...args);
                    if (String(args[0]).startsWith(prefix)) {
                        const read = response.text.bind(response);
                        response.text = async () => { const text = await read(); window.oldModelReceiptRead = true; return text; };
                    }
                    return response;
                };
            }
            """, receiptPrefix);
        var pending = new AtomicReference<Route>();
        page.route("**" + receiptPrefix + "*", pending::set);
        proofButton().click();
        page.waitForCondition(() -> pending.get() != null || !page.locator("#proveDomainExportModel").isDisabled());
        assertNotNull(pending.get(), "the UI must load the retained receipt before displaying model claims");
        assertNull(page.locator("#retainedDomainModelProofStatus").getAttribute("data-status"));
        assertNull(page.locator("#downloadDomainModelProofReceipt").getAttribute("href"));
        assertEquals(0, page.locator("[data-model-index]").count());

        page.locator("[data-domain-export='" + secondId + "']").click();
        waitFor(secondId);
        proofButton().click();
        waitForProof("PROVED_MODEL");
        String currentPath = page.locator("#downloadDomainModelProofReceipt").getAttribute("href");
        String currentValue = page.locator("[data-model-index='100'] [data-model-value]").textContent();
        var oldReceipt = pending.get().fetch();
        assertEquals(200, oldReceipt.status());
        pending.get().fulfill(new Route.FulfillOptions().setResponse(oldReceipt));
        page.waitForFunction("window.oldModelReceiptRead === true");
        assertEquals(secondId, page.locator("#retainedDomainExportDetail").getAttribute("data-run-id"));
        assertEquals("PROVED_MODEL", page.locator("#retainedDomainModelProofStatus").getAttribute("data-status"));
        assertEquals(currentPath, page.locator("#downloadDomainModelProofReceipt").getAttribute("href"));
        assertEquals(currentValue, page.locator("[data-model-index='100'] [data-model-value]").textContent());
    }

    private ObjectNode retainedProof(String runId) throws java.io.IOException {
        String sourcePath = "/api/discovery-domains/exports/" + runId.substring(7);
        var source = JSON.readTree(page.request().get(url(sourcePath)).body());
        var response = page.request().post(url(sourcePath + "/model-proof"), RequestOptions.create()
            .setHeader("Content-Type", "application/json")
            .setData(JSON.writeValueAsString(Map.of("expectedWorkspaceHash", source.path("contentHash").asText(),
                "expectedEvidenceHash", source.at("/evidence/contentHash").asText()))));
        assertEquals(200, response.status(), response.text());
        return (ObjectNode) JSON.readTree(response.body());
    }

    private void assertProofRejected() {
        page.waitForFunction("document.querySelector('#proveDomainExportModel')?.disabled === false");
        String message = page.locator("#retainedDomainModelProofStatus").textContent();
        assertTrue(message.startsWith("Modellprüfung fehlgeschlagen:"), message);
        assertNull(page.locator("#retainedDomainModelProofStatus").getAttribute("data-status"));
        assertNull(page.locator("#downloadDomainModelProofReceipt").getAttribute("href"));
        assertEquals(0, page.locator("[data-model-index]").count());
    }

    private String retain(boolean recurrence, String payload) throws java.io.IOException {
        var domain = recurrence ? new LinearRecurrenceSequenceDomain() : new FiniteDifferenceSequenceDomain();
        var evidence = new DomainDiscoveryRunner().run("model-proof-browser-" + (recurrence ? "recurrence" : "differences"), domain,
            DiscoverySeed.create("source", domain.domainId(), payload, "public browser model control"),
            new DiscoveryBudget(8, 32, 64, 16, 16, 64)).evidence();
        assertEquals(DomainDiscoveryEvidence.Outcome.CONFIRMED, evidence.outcome(), "the real export fixture must retain a candidate");
        Path source = temporary.resolve("source-" + UUID.randomUUID());
        new DomainDiscoveryExport().write(source, evidence);
        return repository.retain(new DomainDiscoveryExportVerifier().requireVerified(source)).runId();
    }

    private Locator proofButton() {
        var button = page.locator("#proveDomainExportModel");
        assertEquals(1, button.count(), "a retained export must expose its checked model proof action");
        assertEquals("Modellgesetz prüfen", button.textContent());
        return button;
    }
    private void open(String runId) {
        page.navigate(url("/discovery-domains.html#export=" + runId.substring(7)));
        waitFor(runId);
    }
    private void waitFor(String runId) {
        page.waitForFunction("id => document.querySelector('#retainedDomainExportDetail')?.dataset.runId === id", runId);
    }
    private void waitForProof(String status) {
        page.waitForFunction("status => document.querySelector('#retainedDomainModelProofStatus')?.dataset.status === status", status);
    }
    private void assertNarrowProofFits(String name) throws java.io.IOException {
        page.setViewportSize(390, 844);
        page.locator("#retainedDomainModelProof").scrollIntoViewIfNeeded();
        assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"));
        assertTrue((Boolean) page.locator("#domainModelProofResult").evaluate("""
            result => Array.from(result.querySelectorAll('.output')).every(node => node.scrollWidth <= node.clientWidth + 1)
            """), "model equations and exact values must wrap without a horizontal scroll area");
        Path screenshot = Path.of("build/reports/domain-model-proof-ui/" + name + ".png");
        Files.createDirectories(screenshot.getParent());
        page.locator("#retainedDomainModelProof").screenshot(new Locator.ScreenshotOptions().setPath(screenshot));
    }
    private String url(String path) { return "http://127.0.0.1:" + server.boundPort() + path; }
}
