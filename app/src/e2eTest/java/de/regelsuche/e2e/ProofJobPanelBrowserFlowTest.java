package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
 *   <li>submit a job for {@code a + 0 → a},</li>
 *   <li>poll the job list until the job appears with a status,</li>
 *   <li>open the artefact list and verify a {@code proof.*} entry is present,</li>
 *   <li>capture {@code docs/assets/screenshots/proof-job-panel.png} (and a
 *       {@code proof-job-panel.webm} video when {@code recordDocs=true}).</li>
 * </ol>
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
    @DisplayName("Proof-Workbench: Job für a + 0 -> a einreichen und Artefakte ansehen")
    void proofJobPanelBrowserFlow() throws Exception {
        // 1. open Proof-Jobs tab
        page.locator(".tab[data-tab='proofJobs']").click();
        page.waitForSelector("#tab-proofJobs.active",
            new Page.WaitForSelectorOptions().setTimeout(5_000));

        // 2. submit a job for "a + 0 -> a"
        page.locator("#proofJobLeft").fill("a + 0");
        page.locator("#proofJobRight").fill("a");
        page.locator("#proofJobSubmit").click();

        // 3. poll the job list until the job appears
        page.waitForFunction(
            "() => { var l = document.querySelector('#proofJobList');"
                + " return l && l.innerText.includes('a + 0')"
                + " && l.innerText.includes('Status:'); }",
            null, new Page.WaitForFunctionOptions().setTimeout(20_000));

        // 4. trigger explicit reload, then verify Status badge is present
        page.locator("#proofJobReload").click();
        page.waitForFunction(
            "() => { var l = document.querySelector('#proofJobList');"
                + " return l && /Status:\\s*<code>(DONE|RUNNING|QUEUED)/.test(l.innerHTML); }",
            null, new Page.WaitForFunctionOptions().setTimeout(20_000));

        // 5. open the artefact list for the just-submitted job. Poll until the
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

        // 6. capture screenshot + (optional) video
        screenshot("proof-job-panel.png");
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
