package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.FilePayload;
import de.regelsuche.discovery.domain.*;
import de.regelsuche.discovery.domain.DiscoveryDomain.*;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.web.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DomainExportWorkspaceBrowserTest {
    @TempDir Path temporary;
    private WebWorkbenchServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;
    private String previous;
    private String firstId, secondId;
    private FilePayload[] files;
    private byte[] evidenceBytes;
    private final List<String> errors = new ArrayList<>();

    @BeforeEach void start() throws Exception {
        previous = System.getProperty("regelsuche.discovery.runs.directory");
        System.setProperty("regelsuche.discovery.runs.directory", temporary.resolve("runs").toString());
        Path source = temporary.resolve("source");
        firstId = export(source, "observed=1,4,9,16;holdout=25,36").manifest().contentHash();
        files = new FilePayload[4]; int index = 0;
        for (String name : List.of("domain.json", "evidence.json", "lifecycle-handoff.json", "export-manifest.json")) {
            byte[] bytes = Files.readAllBytes(source.resolve(name));
            files[index++] = new FilePayload(name, "application/json", bytes);
            if (name.equals("evidence.json")) evidenceBytes = bytes;
        }
        server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), WebSecurityConfig.none());
        server.start(); playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1300, 900));
        page.onPageError(errors::add);
    }

    @AfterEach void stop() {
        try { assertTrue(errors.isEmpty(), errors.toString()); }
        finally {
            if (browser != null) browser.close(); if (playwright != null) playwright.close();
            if (server != null) server.stop();
            if (previous == null) System.clearProperty("regelsuche.discovery.runs.directory");
            else System.setProperty("regelsuche.discovery.runs.directory", previous);
        }
    }

    @Test void importsReviewsReplaysAndDownloadsTheOriginalSequenceExport() throws Exception {
        page.navigate(url("/discovery-domains.html"));
        assertEquals(1, page.locator("#importDomainExport").count(), "retained export import must be available");
        page.locator("#importDomainExport").setInputFiles(files);
        waitFor(firstId);
        assertEquals(5, page.locator("#retainedDomainExportDetail [data-resource]").count());
        String detail = page.locator("#retainedDomainExportDetail").textContent();
        assertTrue(detail.contains("observedTerms")); assertTrue(detail.contains("holdoutTerms"));
        assertTrue(detail.contains("FINITE_DIFFERENCE_WITNESS")); assertTrue(detail.contains("NOT_EVALUATED"));
        assertTrue(detail.contains("EXTERNAL_NOVELTY")); assertTrue(detail.contains("NOT_PRODUCED"));
        assertTrue(detail.contains("<img src=x onerror=alert(1)> public browser control"));
        assertEquals(0, page.locator("#retainedDomainExportDetail img").count(), "source reference must remain plain text");
        assertTrue(page.url().contains("export=" + firstId.substring(7)));
        page.locator("#replayDomainExport").click();
        page.waitForFunction("document.querySelector('#retainedDomainReplayStatus')?.dataset.status === 'IDENTICAL_CANONICAL_EVIDENCE'");
        assertEquals(firstId, page.locator("#retainedDomainExportDetail").getAttribute("data-run-id"));
        var download = page.waitForDownload(() -> page.locator("[data-original-file='evidence.json']").click());
        Path downloaded = temporary.resolve("downloaded-evidence.json"); download.saveAs(downloaded);
        assertArrayEquals(evidenceBytes, Files.readAllBytes(downloaded));
        page.reload(); waitFor(firstId);
        page.setViewportSize(390, 844);
        assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"));
        Path screenshot = Path.of("build/reports/domain-export-ui/sequence-narrow.png"); Files.createDirectories(screenshot.getParent());
        page.locator("#retainedDomainExportDetail").scrollIntoViewIfNeeded();
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshot));
    }

    @Test void lateReplayCannotReplaceTheNewerSelectedExport() throws Exception {
        var repository = new DomainExportWorkspaceRepository(temporary.resolve("runs-domain-exports"));
        repository.retain(export(temporary.resolve("first"), "observed=1,4,9,16;holdout=25,36"));
        secondId = repository.retain(export(temporary.resolve("second"), "observed=1,4,9,16;holdout=26")).runId();
        page.navigate(url("/static/discovery-domains.html#export=" + firstId.substring(7)));
        assertEquals(1, page.locator("#importDomainExport").count(), "retained export selection must be available");
        waitFor(firstId);
        page.evaluate("""
            () => {
                const original = window.fetch;
                window.fetch = async (...args) => {
                    const response = await original(...args);
                    if (String(args[0]).endsWith('/replay')) {
                        const read = response.text.bind(response);
                        response.text = async () => { const text = await read(); window.oldReplayRead = true; return text; };
                    }
                    return response;
                };
            }
            """);
        var pending = new java.util.concurrent.atomic.AtomicReference<Route>();
        page.route("**/replay", pending::set);
        page.locator("#replayDomainExport").click(); page.waitForCondition(() -> pending.get() != null);
        page.locator("[data-domain-export='" + secondId + "']").click(); waitFor(secondId);
        var response = pending.get().fetch(); assertEquals(200, response.status());
        pending.get().fulfill(new Route.FulfillOptions().setResponse(response));
        page.waitForFunction("window.oldReplayRead === true");
        assertEquals(secondId, page.locator("#retainedDomainExportDetail").getAttribute("data-run-id"));
        assertFalse(page.locator("#retainedDomainReplayStatus").textContent().contains("reproduziert"));
        assertTrue(page.locator("#retainedDomainExportDetail").textContent().contains("REFUTED"));
    }

    private DomainDiscoveryExportVerifier.VerifiedDomainExport export(Path path, String payload) {
        var domain = new FiniteDifferenceSequenceDomain();
        var evidence = new DomainDiscoveryRunner().run("sequence-browser-control", domain,
            DiscoverySeed.create("source", domain.domainId(), payload, "<img src=x onerror=alert(1)> public browser control"),
            new DiscoveryBudget(4, 16, 32, 8, 8, 32)).evidence();
        new DomainDiscoveryExport().write(path, evidence);
        return new DomainDiscoveryExportVerifier().requireVerified(path);
    }
    private void waitFor(String runId) { page.waitForFunction("id => document.querySelector('#retainedDomainExportDetail')?.dataset.runId === id", runId); }
    private String url(String path) { return "http://127.0.0.1:" + server.boundPort() + path; }
}
