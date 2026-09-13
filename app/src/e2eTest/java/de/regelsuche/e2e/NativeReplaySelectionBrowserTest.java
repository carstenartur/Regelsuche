package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import de.regelsuche.discovery.representation.TargetFreeRepresentationDiscoveryRun;
import de.regelsuche.discovery.representation.TargetFreeSearchExecution;
import de.regelsuche.discovery.representation.TargetFreeSearchExecution.RunResult;
import de.regelsuche.discovery.representation.TargetFreeSearchExecution.State;
import de.regelsuche.discovery.representation.TargetFreeSearchExecution.Transition;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.web.WebSecurityConfig;
import de.regelsuche.web.WebWorkbenchServer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real retained traces and HTTP responses; selection never creates mathematical evidence. */
class NativeReplaySelectionBrowserTest {
    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    private static final SearchHeuristic BUDGET = new SearchHeuristic(3, 12, 1, 1, 8, 8);
    @TempDir Path temporary;
    private RunResult run;
    private WebWorkbenchServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;
    private String previousDirectory;
    private final List<String> errors = new ArrayList<>();
    private final Map<Path, byte[]> originals = new LinkedHashMap<>();

    @BeforeEach void start() throws Exception {
        run = retain("(x + 0) * (x + 0)");
        previousDirectory = System.getProperty("regelsuche.discovery.runs.directory");
        System.setProperty("regelsuche.discovery.runs.directory", temporary.resolve("runs").toString());
        server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), WebSecurityConfig.none());
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        assertTrue(browser.version().startsWith("148."), "requires the checkout-pinned Chromium 148");
        System.out.println("nativeReplaySelectionBrowser=" + browser.version());
        System.out.println("nativeReplaySelectionPlaywright=" + Playwright.class.getProtectionDomain().getCodeSource().getLocation());
        page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1360, 1000));
        page.setDefaultTimeout(10_000);
        page.onPageError(errors::add);
    }

    @AfterEach void stop() throws Exception {
        try {
            assertTrue(errors.isEmpty(), errors.toString());
            for (var entry : originals.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()),
                "UI selection changed original retained bytes: " + entry.getKey().getFileName());
        } finally {
            if (browser != null) browser.close();
            if (playwright != null) playwright.close();
            if (server != null) server.stop();
            if (previousDirectory == null) System.clearProperty("regelsuche.discovery.runs.directory");
            else System.setProperty("regelsuche.discovery.runs.directory", previousDirectory);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"/", "/index.html", "/static/index.html"})
    void zeroGenerationDeepLinksRoundTripWithoutChangingTheCandidate(String entry) throws Exception {
        var zero = retain("x + 0");
        var candidate = zero.artifact().content().states().stream().filter(s -> s.generationSequences().equals(List.of(0)))
            .findFirst().orElseThrow(() -> new AssertionError("public fixture must visit generation zero"));
        open(entry, zero, candidate, "&generation=0&artifact=PATH_REPLAY");
        assertEquals("0", parameter("generation"), "the current controller silently drops the requested generation");
        assertSelection(zero, candidate, 0);
        page.reload();
        ready(candidate);
        assertSelection(zero, candidate, 0);
        assertEquals("PATH_REPLAY", parameter("artifact"));
        page.locator("[data-artifact-role='PROOF_OBLIGATIONS'] button").click();
        assertSelection(zero, candidate, 0);
        assertTrue(page.locator("#candidateDossierDetail").textContent().contains("NOT_PRODUCED"));
    }

    @Test void earlierStepAndGraphKeepTheEndCandidateThroughKeyboardReloadAndNarrowViewport() throws Exception {
        var candidate = multiStep(run);
        int first = candidate.generationSequences().getFirst(), next = candidate.generationSequences().get(1);
        open("/", run, candidate, "&generation=" + first + "&artifact=PATH_REPLAY");
        assertEquals(String.valueOf(first), parameter("generation"));
        assertSelection(run, candidate, first);
        var button = page.locator("button[data-replay-generation='" + next + "']");
        button.focus(); page.keyboard().press("Enter");
        assertSelection(run, candidate, next);
        assertEquals(String.valueOf(next), page.evaluate("document.activeElement.dataset.replayGeneration"));
        page.locator("#nativeReplayPrevious").click();
        assertSelection(run, candidate, first);
        page.locator("#nativeReplayGraph").click();
        assertEquals("SEARCH_GRAPH", parameter("artifact"));
        assertEquals(String.valueOf(edge(run, candidate, first).sequence()), parameter("edge"));
        assertEquals(candidate.stateId(), page.locator("#candidateDossierDetail").getAttribute("data-candidate-id"));
        assertTrue(page.locator("#candidateGraphEvidence").textContent().contains(edge(run, candidate, first).toStateId()));
        page.locator("#nativeGraphReplay").click();
        assertEquals("PATH_REPLAY", parameter("artifact"));
        assertEquals(String.valueOf(first), page.evaluate("document.activeElement.dataset.replayGeneration"));
        page.reload(); ready(candidate); assertSelection(run, candidate, first);
        Path evidence = Path.of("build/reports/native-replay-selection"); Files.createDirectories(evidence);
        page.locator("#nativeReplaySelection").evaluate("node => node.scrollIntoView({block: 'start'})");
        page.screenshot(new Page.ScreenshotOptions().setPath(evidence.resolve("earlier-step-desktop.png")));
        page.setViewportSize(390, 844);
        page.locator("#nativeReplaySelection").evaluate("node => node.scrollIntoView({block: 'start'})");
        assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"));
        page.screenshot(new Page.ScreenshotOptions().setPath(evidence.resolve("earlier-step-390.png")));
        var other = run.artifact().content().states().stream().filter(s -> !s.generationSequences().isEmpty()
            && !s.stateId().equals(candidate.stateId())).findFirst().orElseThrow();
        page.locator("button[data-candidate-id='" + other.stateId() + "']").click();
        assertNull(parameter("generation"), "a candidate change must clear the old generation");
        assertNull(parameter("edge"), "a candidate change must clear the old context edge");
    }

    @Test void repeatedSubtermsKeepTheirDifferentActualOccurrences() {
        var generations = run.artifact().content().generations();
        // The retained path rewrites the right duplicate and then the remaining left occurrence.
        // A generated left rewrite at the root need not itself become a visited state.
        var candidate = run.artifact().content().states().stream().filter(s -> s.generationSequences().size() >= 2)
            .filter(s -> generations.get(s.generationSequences().getFirst()).occurrencePath().equals(List.of(1))
                && generations.get(s.generationSequences().get(1)).occurrencePath().equals(List.of(0)))
            .findFirst().orElseThrow(() -> new AssertionError("public fixture must retain right-then-left occurrence rewrites"));
        var first = generations.get(candidate.generationSequences().getFirst());
        var second = generations.get(candidate.generationSequences().get(1));
        assertEquals(run.workspace().input().displayText(), first.sourceExpression());
        assertEquals(first.sourceOccurrenceExpression(), second.sourceOccurrenceExpression());
        var positions = new ArrayList<List<Integer>>();
        for (int generation : candidate.generationSequences().subList(0, 2)) {
            open("/", run, candidate, "&generation=" + generation);
            assertEquals(String.valueOf(generation), parameter("generation"));
            assertSelection(run, candidate, generation);
            positions.add(generations.get(generation).occurrencePath());
        }
        assertEquals(List.of(List.of(1), List.of(0)), positions);
    }

    @Test void foreignGenerationsAndConflictingEdgesNeverRenderASubstituteSelection() {
        var candidate = multiStep(run);
        int first = candidate.generationSequences().getFirst();
        int foreign = run.artifact().content().generations().stream().mapToInt(TargetFreeSearchExecution.Generation::sequence)
            .filter(i -> !candidate.generationSequences().contains(i)).findFirst().orElseThrow();
        int lastEdge = edge(run, candidate, candidate.generationSequences().getLast()).sequence();
        for (String query : List.of("&generation=" + foreign, "&generation=" + first + "&edge=" + lastEdge,
                "&generation=999999", "&generation=-1", "&generation=00")) {
            page.navigate(url("/" + fragment(run, candidate) + query));
            page.waitForFunction("document.querySelector('#candidateDossierDetail') || document.querySelector('#candidateDossierStatus.run-error') || document.querySelector('#retainedRunStatus.run-error')");
            assertEquals(0, page.locator("#candidateDossierDetail").count(), "invalid generation was silently ignored: " + query);
            assertFalse(page.locator("#retainedRunDetail").textContent().contains("Ausgewählter Replay-Schritt"));
        }
        // Old links without generation preserve their incoming-edge-only interpretation.
        open("/", run, candidate, "&edge=" + lastEdge + "&artifact=SEARCH_GRAPH");
        assertEquals(String.valueOf(lastEdge), parameter("edge"));
        assertTrue(page.locator("#candidateGraphEvidence").textContent().contains(candidate.stateId()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lateHistoricalDossierSuccessOrErrorCannotReplaceNewerRunStepOrFocus(boolean failed) throws Exception {
        var later = retain("(y + 0) * (y + 0)");
        var initialCandidate = multiStep(run);
        var laterCandidate = multiStep(later);
        var pending = new AtomicReference<Route>();
        String suffix = "/api/discovery-runs/" + digest(run) + "/dossier";
        page.navigate(url("/")); markResponseRead(suffix);
        page.route("**" + suffix, pending::set);
        page.evaluate("hash => { location.hash = hash; }", fragment(run, initialCandidate) + "&generation=" + initialCandidate.generationSequences().getFirst());
        page.waitForCondition(() -> pending.get() != null);
        page.evaluate("hash => { location.hash = hash; }", fragment(later, laterCandidate) + "&generation=" + laterCandidate.generationSequences().getFirst());
        ready(laterCandidate);
        int selected = laterCandidate.generationSequences().getLast();
        page.locator("button[data-replay-generation='" + selected + "']").focus(); page.keyboard().press("Enter");
        var before = uiSnapshot();
        var response = pending.get().fetch(); assertEquals(200, response.status());
        if (failed) pending.get().fulfill(new Route.FulfillOptions().setStatus(503).setContentType("application/json")
            .setBody("{\"code\":\"UNAVAILABLE\",\"message\":\"delayed failure\"}"));
        else pending.get().fulfill(new Route.FulfillOptions().setResponse(response));
        page.waitForFunction("suffix => window.__replaySelectionReads[suffix] === true", suffix);
        assertEquals(before, uiSnapshot(), "late response changed current run, selection or keyboard focus");
        assertSelection(later, laterCandidate, selected);
    }

    @Test void lateDuplicateCannotReplaceANewerStepWithinTheSameCandidate() {
        var candidate = multiStep(run);
        int first = candidate.generationSequences().getFirst(), next = candidate.generationSequences().get(1);
        open("/", run, candidate, "&generation=" + first);
        assertEquals(String.valueOf(first), parameter("generation"));
        String suffix = "/api/discovery-runs/" + digest(run) + "/duplicate";
        markResponseRead(suffix);
        var pending = new AtomicReference<Route>(); page.route("**" + suffix, pending::set);
        page.locator("#duplicateRunSeed").fill("9"); page.locator("#duplicateRetainedRun button").click();
        page.waitForCondition(() -> pending.get() != null);
        page.locator("button[data-replay-generation='" + next + "']").focus(); page.keyboard().press("Enter");
        var before = uiSnapshot();
        var response = pending.get().fetch(); assertEquals(201, response.status());
        pending.get().fulfill(new Route.FulfillOptions().setResponse(response));
        page.waitForFunction("suffix => window.__replaySelectionReads[suffix] === true", suffix);
        assertEquals(before, uiSnapshot());
        assertSelection(run, candidate, next);
    }

    private RunResult retain(String expression) throws Exception {
        var result = TargetFreeRepresentationDiscoveryRun.writeTargetFree(temporary.resolve("runs"), expression, BUDGET, REVISION);
        try (var paths = Files.walk(temporary)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) originals.putIfAbsent(path, Files.readAllBytes(path));
        }
        return result;
    }
    private static State multiStep(RunResult run) {
        return run.artifact().content().states().stream().filter(s -> s.generationSequences().size() >= 2).findFirst()
            .orElseThrow(() -> new AssertionError("public fixture must retain a real multi-step path"));
    }
    private static Transition edge(RunResult run, State candidate, int generation) {
        var prefix = candidate.generationSequences().subList(0, candidate.generationSequences().indexOf(generation) + 1);
        var target = run.artifact().content().states().stream().filter(s -> s.generationSequences().equals(prefix)).findFirst().orElseThrow();
        return run.artifact().content().transitions().stream().filter(t -> t.toStateId().equals(target.stateId())).findFirst().orElseThrow();
    }
    private void assertSelection(RunResult selectedRun, State candidate, int generation) {
        assertEquals(selectedRun.workspace().runId(), page.locator("#retainedRunId").textContent());
        assertEquals(candidate.stateId(), page.locator("#candidateDossierDetail").getAttribute("data-candidate-id"));
        assertEquals(String.valueOf(generation), parameter("generation"));
        var selected = page.locator("#nativeReplaySelection");
        assertEquals(String.valueOf(generation), selected.getAttribute("data-generation-sequence"));
        var observed = selectedRun.artifact().content().generations().get(generation);
        assertTrue(selected.textContent().contains(observed.executionHash()));
        assertTrue(selected.textContent().contains(observed.sourceOccurrenceExpression()));
        assertEquals(observed.occurrencePath().isEmpty() ? "root" : observed.occurrencePath().stream().map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(".")), selected.locator("[data-replay-occurrence]").textContent());
    }
    private void open(String entry, RunResult selectedRun, State candidate, String query) {
        page.navigate(url(entry + fragment(selectedRun, candidate) + query)); ready(candidate);
    }
    private void ready(State candidate) {
        page.waitForFunction("id => document.querySelector('#candidateDossierDetail')?.dataset.candidateId === id", candidate.stateId());
    }
    private static String fragment(RunResult selectedRun, State candidate) {
        return "#run=" + digest(selectedRun) + "&candidate=" + candidate.stateId();
    }
    private static String digest(RunResult selectedRun) { return selectedRun.workspace().runId().substring(7); }
    private String url(String path) { return "http://127.0.0.1:" + server.boundPort() + path; }
    private Object parameter(String name) { return page.evaluate("name => new URLSearchParams(location.hash.slice(1)).get(name)", name); }
    private Object uiSnapshot() {
        return page.evaluate("({url: location.hash, run: document.querySelector('#retainedRunId').textContent, candidate: document.querySelector('#candidateDossierDetail').dataset.candidateId, generation: document.querySelector('#nativeReplaySelection').dataset.generationSequence, focus: document.activeElement.dataset.replayGeneration})");
    }
    private void markResponseRead(String suffix) {
        page.evaluate("""
            suffix => {
                window.__replaySelectionReads = {};
                const original = window.fetch;
                window.fetch = async (...args) => {
                    const response = await original(...args);
                    if (String(args[0]).endsWith(suffix)) {
                        const read = response.text.bind(response);
                        response.text = async () => {
                            const raw = await read();
                            queueMicrotask(() => { window.__replaySelectionReads[suffix] = true; });
                            return raw;
                        };
                    }
                    return response;
                };
            }
            """, suffix);
    }
}
