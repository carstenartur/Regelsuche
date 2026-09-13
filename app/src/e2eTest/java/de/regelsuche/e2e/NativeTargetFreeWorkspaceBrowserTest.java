package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.microsoft.playwright.*;
import de.regelsuche.discovery.representation.*;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.web.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeTargetFreeWorkspaceBrowserTest {
    @TempDir Path temporary;

    @Test void followsTheRealPersistedCandidateOccurrenceThroughGraphAndReplayAfterReload() throws Exception {
        var result = TargetFreeRepresentationDiscoveryRun.writeTargetFree(temporary.resolve("runs"), "(x + 0) * (x + 0)",
            new SearchHeuristic(3, 12, 1, 1, 8, 8), "0123456789abcdef0123456789abcdef01234567");
        String previous = System.getProperty("regelsuche.discovery.runs.directory");
        System.setProperty("regelsuche.discovery.runs.directory", temporary.resolve("runs").toString());
        var server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), WebSecurityConfig.none());
        var errors = new ArrayList<String>();
        try (var playwright = Playwright.create()) {
            server.start();
            try (var browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
                System.out.println("nativeWorkspaceBrowserVersion=" + browser.version());
                System.out.println("nativeWorkspacePlaywrightJar=" + Playwright.class.getProtectionDomain().getCodeSource().getLocation());
                assertTrue(browser.version().startsWith("148."), "requires the pinned Chromium 148 browser");
                var page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1360, 1000));
                page.onPageError(errors::add);
                page.navigate("http://127.0.0.1:" + server.boundPort() + "/#run=" + result.workspace().runId().substring(7));
                page.waitForFunction("document.querySelector('#candidateDossierDetail')?.textContent.includes('UNTARGETED')");
                assertTrue(page.locator("#candidateDossierDetail").textContent().contains("NOT_EVALUATED"));
                assertTrue(page.locator("#nativeExecutionReplay").textContent().contains("Tatsächliche Quellposition"));
                var first = result.artifact().content().states().stream().filter(s -> !s.generationSequences().isEmpty()).findFirst().orElseThrow();
                assertEquals(first.stateId(), page.locator("#candidateDossierDetail").getAttribute("data-candidate-id"));
                var generation = result.artifact().content().generations().get(first.generationSequences().getFirst());
                assertTrue(page.locator("#nativeExecutionReplay").textContent().contains(generation.executionHash()));
                page.locator("[data-dossier-edge]").first().click();
                assertTrue(page.url().contains("artifact=SEARCH_GRAPH"));
                assertTrue(page.locator("#candidateGraphEvidence").textContent().contains(generation.applicationKey()));
                assertEquals(generation.occurrencePath().isEmpty() ? "root" : generation.occurrencePath().stream()
                        .map(String::valueOf).collect(java.util.stream.Collectors.joining(".")),
                    page.locator("#candidateGraphEvidence tr").filter(new Locator.FilterOptions().setHasText("Quellposition"))
                        .locator("td").textContent());
                page.reload();
                page.waitForFunction("document.querySelector('#candidateGraphEvidence') !== null");
                assertEquals(first.stateId(), page.locator("#candidateDossierDetail").getAttribute("data-candidate-id"));
                try (var script = getClass().getResourceAsStream("/native-search-dossier-controls.js")) {
                    assertNotNull(script, "native dossier controls must be available on the test classpath");
                    page.addScriptTag(new Page.AddScriptTagOptions().setContent(
                        new String(script.readAllBytes(), StandardCharsets.UTF_8)));
                }
                var json = new JsonMapper();
                assertEquals("native trace, occurrence, graph/replay and stale-response controls passed", page.evaluate(
                    "([run, artifact]) => window.nativeSearchDossierControls(run, artifact)",
                    List.of(json.readValue(result.workspace().toCanonicalJson(), Map.class), json.readValue(result.artifact().toCanonicalJson(), Map.class))));
                page.setViewportSize(390, 844);
                assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"));
                Path output = Path.of("build/reports/native-target-free-workspace"); Files.createDirectories(output);
                page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("native-trace-dossier.png")));
                assertTrue(errors.isEmpty(), errors.toString());
            }
        } finally {
            server.stop();
            if (previous == null) System.clearProperty("regelsuche.discovery.runs.directory");
            else System.setProperty("regelsuche.discovery.runs.directory", previous);
        }
    }
}
