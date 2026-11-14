package de.regelsuche.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.proof.ProofWorker;
import de.regelsuche.validation.CandidateProofStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * End-to-end coverage for the Proof-Jobs workbench tab.
 *
 * <p>Boots the application with an in-process proof workbench backed by the
 * deterministic {@code StubAlwaysSucceedsWorker} so the full submit →
 * schedule → artefact-write loop runs without needing Z3 or Lean on the CI
 * runner. The flow:</p>
 * <ol>
 *   <li>open the Proof-Jobs tab,</li>
 *   <li>submit the Sophie-Germain identity
 *       {@code a^4 + 4b^4 = (a^2 - 2ab + 2b^2)(a^2 + 2ab + 2b^2)},</li>
 *   <li>poll the job list until the job appears with a status,</li>
 *   <li>open the artefact list and verify a {@code proof.*} entry is present,</li>
 *   <li>capture {@code docs/assets/screenshots/proof-job-panel.png} (and a
 *       {@code proof-job-panel.webm} video when {@code recordDocs=true}).</li>
 * </ol>
 *
 * <p>The current SMT bridge expands bounded non-negative integral powers into
 * ordinary nonlinear real arithmetic, so the browser flow uses the readable
 * exponent notation shown to users. The stub characterizes the browser,
 * queue, scheduler and artifact flow. Real solver correctness remains covered
 * by the dedicated proof-worker and proof-image tests.</p>
 */
class ProofJobPanelBrowserFlowTest {

    private static final boolean RECORD_DOCS = Boolean.parseBoolean(
        System.getProperty("regelsuche.recordDocs", "false"));

    private static final Path DOCS_ROOT = Paths.get("..", "docs", "assets")
        .toAbsolutePath().normalize();
    private static final Path SCREENSHOT_DIR = DOCS_ROOT.resolve("screenshots");
    private static final Path VIDEO_DIR = DOCS_ROOT.resolve("videos");

    private static RegelsucheAppEnvironment app;
    private static Playwright playwright;
    private static Browser browser;

    private BrowserContext context;
    private Page page;

    @BeforeAll
    static void boot() throws IOException {
        // Proof workbench enabled — needed for the /api/proof/jobs surface to
        // accept submissions (otherwise it would 503).
        app = new RegelsucheAppEnvironment(true);
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions().setHeadless(true));
        if (RECORD_DOCS) {
            Files.createDirectories(SCREENSHOT_DIR);
            Files.createDirectories(VIDEO_DIR);
        }
    }

    @AfterAll
    static void teardown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (app != null) app.close();
    }

    @BeforeEach
    void openContext() {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
            .setViewportSize(1400, 900)
            .setAcceptDownloads(true);
        if (RECORD_DOCS) {
            options.setRecordVideoDir(VIDEO_DIR).setRecordVideoSize(1400, 900);
        }
        context = browser.newContext(options);
        page = context.newPage();
        page.navigate(app.baseUrl());
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    @AfterEach
    void closeContext(TestInfo info) {
        Path videoPath = null;
        try {
            if (RECORD_DOCS && page != null && page.video() != null) {
                videoPath = page.video().path();
            }
        } catch (Exception ignored) { /* page may already be detached */ }
        if (page != null) page.close();
        if (context != null) context.close();
        if (RECORD_DOCS && videoPath != null && Files.exists(videoPath)) {
            try {
                Path target = VIDEO_DIR.resolve(info.getTestMethod()
                    .orElseThrow().getName().replace("BrowserFlow", "")
                    .replaceAll("(?<!^)([A-Z])", "-$1").toLowerCase()
                    + ".webm");
                Files.move(videoPath, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) { /* keep auto-generated name */ }
        }
    }

    @Test
    @DisplayName("Proof-Workbench: Sophie-Germain-Identität durch Queue und Artefakte")
    void proofJobPanelBrowserFlow() throws Exception {
        openProofJobs();

        // Submit the Sophie-Germain identity.
        page.locator("#proofJobLeft").fill("a^4 + 4*b^4");
        page.locator("#proofJobRight").fill(
            "(a^2 - 2*a*b + 2*b^2)*(a^2 + 2*a*b + 2*b^2)");
        page.locator("#proofJobSubmit").click();

        // 4. poll the job list until the job appears
        page.waitForFunction(
            "() => { var l = document.querySelector('#proofJobList');"
                + " return l && l.innerText.includes('a^4 + 4*b^4')"
                + " && l.innerText.includes('(a^2 - 2*a*b + 2*b^2)')"
                + " && l.innerText.includes('Status:'); }",
            null, new Page.WaitForFunctionOptions().setTimeout(20_000));

        // 5. trigger explicit reload, then verify Status badge is present
        page.locator("#proofJobReload").click();
        page.waitForFunction(
            "() => { var l = document.querySelector('#proofJobList');"
                + " return l && /Status:\\s*<code>(DONE|RUNNING|QUEUED)/.test(l.innerHTML); }",
            null, new Page.WaitForFunctionOptions().setTimeout(20_000));

        // 6. open the artefact list for the just-submitted job. Poll until the
        // stub worker has written the bundle (scheduler runs every ~250ms).
        boolean foundArtifact = false;
        for (int attempt = 0; attempt < 30 && !foundArtifact; attempt++) {
            page.locator(".proof-artifacts").first().click();
            // Give the artefact panel a tick to render the fetched list.
            page.waitForFunction(
                "() => { var l = document.querySelector('#proofJobArtifacts');"
                    + " return l && l.innerHTML.includes('Bundle für Job'); }",
                null, new Page.WaitForFunctionOptions().setTimeout(5_000));
            String artifactPanel = page.locator("#proofJobArtifacts").innerText();
            if (artifactPanel.contains("proof.")
                || artifactPanel.contains("metadata.json")
                || artifactPanel.contains("stdout.txt")) {
                foundArtifact = true;
                break;
            }
            page.waitForTimeout(500);
        }
        assertTrue(foundArtifact,
            "artefact panel must show at least one proof.* / metadata / stdout entry");

        // 7. capture screenshot + (optional) video
        screenshot("proof-job-panel.png");
    }

    @Test
    void cancelsRunningProofJobAndRetainsTerminalRecord() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var exit = new CountDownLatch(1);
        var worker = new ProofWorker() {
            public String workerId() { return "browser-cancellation-fixture"; }
            public Result prove(RuleCandidate candidate, List<Assumption> assumptions) {
                entered.countDown();
                try {
                    exit.await();
                } catch (InterruptedException requested) {
                    interrupted.countDown();
                    try {
                        exit.await();
                    } catch (InterruptedException shutdown) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new Result(candidate, CandidateProofStatus.FORMALLY_PROVABLE, "", workerId(), 0);
            }
        };

        try (var controlled = new RegelsucheAppEnvironment(worker)) {
            try {
                page.navigate(controlled.baseUrl());
                openProofJobs();
                page.locator("#proofJobLeft").fill("x+0");
                page.locator("#proofJobRight").fill("x");
                var submitted = page.waitForResponse(response ->
                    response.request().method().equals("POST") && response.url().endsWith("/api/proof/jobs"),
                    () -> page.locator("#proofJobSubmit").click());
                assertEquals(201, submitted.status());
                var json = new JsonMapper();
                String id = json.readTree(submitted.body()).required("jobId").textValue();
                var row = page.locator("#proofJobList .item").filter(
                    new Locator.FilterOptions().setHasText(id));

                assertTrue(entered.await(5, TimeUnit.SECONDS), "worker did not start");
                page.locator("#proofJobReload").click();
                assertThat(row.locator("code").first()).hasText("RUNNING");
                assertThat(row).containsText("Gestartete Versuche: 1");
                assertThat(row).containsText("Zeitlimit je Versuch: 10000 ms");
                var cancel = row.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Abbrechen"));
                var request = page.waitForResponse(response -> response.request().method().equals("POST")
                    && response.url().endsWith("/api/proof/jobs/" + id + "/cancel"), cancel::click);
                assertEquals(200, request.status());
                var stop = json.readTree(request.body());
                assertEquals(id, stop.required("id").textValue());
                String requestedAt = stop.required("cancellationRequestedAt").textValue();
                assertTrue(interrupted.await(5, TimeUnit.SECONDS), "worker did not receive the interrupt");
                assertThat(row.locator("code").first()).hasText("CANCELLING");
                assertThat(row).containsText("Worker-Beendigung ausstehend");
                assertThat(row).containsText(requestedAt);
                assertThat(cancel).hasCount(0);

                exit.countDown();
                // The worker and the HTTP response can finish in either order. Use the
                // same explicit refresh control available to the user until exit is visible.
                for (int retry = 0; retry < 20; retry++) {
                    var list = page.waitForResponse(controlled.baseUrl() + "/api/proof/jobs",
                        () -> page.locator("#proofJobReload").click());
                    var job = json.readTree(list.body()).required("jobs").get(0);
                    assertEquals(id, job.required("id").textValue());
                    if ("CANCELLED".equals(job.required("status").textValue())) break;
                }
                assertThat(row.locator("code").first()).hasText("CANCELLED");
                assertThat(row).containsText("Beendet:");
                assertThat(row).containsText("USER_CANCELLED");
                assertThat(row).containsText(requestedAt);
                assertThat(row).containsText("Gestartete Versuche: 1");
                assertThat(cancel).hasCount(0);

                page.reload();
                assertThat(page.locator("#tab-proofJobs")).isVisible();
                page.locator("#proofJobReload").click();
                assertThat(row.locator("code").first()).hasText("CANCELLED");
                assertThat(row).containsText(requestedAt);
                row.locator(".proof-artifacts").click();
                assertThat(page.locator("#proofJobArtifacts")).containsText("Bundle für Job");
                assertThat(page.locator("#proofJobArtifacts")).containsText(id);
                assertThat(page.locator("#proofJobArtifacts")).containsText("Noch keine Artefakte");
                assertThat(page.locator("#proofJobArtifacts a")).hasCount(0);
            } finally {
                exit.countDown();
            }
        }
    }

    private void openProofJobs() {
        // Start the primary search to reveal the Proof-Jobs tab.
        page.locator("#searchForm input[name='expression']").fill("x + 0");
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction(
            "() => document.body.dataset.preSearch === 'false'",
            null, new Page.WaitForFunctionOptions().setTimeout(10_000));

        page.locator(".tab[data-tab='proofJobs']").click();
        page.waitForSelector("#tab-proofJobs.active",
            new Page.WaitForSelectorOptions().setTimeout(5_000));
    }

    private void screenshot(String fileName) {
        if (!RECORD_DOCS) return;
        Path target = SCREENSHOT_DIR.resolve(fileName);
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException ignored) { /* best-effort */ }
        page.screenshot(new Page.ScreenshotOptions()
            .setPath(target)
            .setType(ScreenshotType.PNG)
            .setFullPage(true));
        assertNotNull(target);
    }
}
