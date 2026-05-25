package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitForSelectorState;
import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner;
import de.regelsuche.benchmark.DiscoveryReplayArtifactWriter;
import de.regelsuche.example.SeedExpression;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryReportReplayBrowserTest {
    @TempDir
    Path tempDir;

    @Test
    void discoveryReportPageRendersMetricCardsAndArtifactsExist() throws Exception {
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report = sampleReport();
        DiscoveryReplayArtifactWriter.ArtifactBundle bundle =
            new DiscoveryReplayArtifactWriter().write(report, tempDir);

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.setContent(Files.readString(bundle.htmlReport()));
            page.waitForSelector(".card", new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE));

            assertTrue(page.locator("text=searchSpaceSize").count() > 0);
            assertTrue(page.locator("text=proofSuccessRate").count() > 0);
            assertTrue(page.locator("text=artifactCounts").count() > 0);
        }
        assertTrue(Files.size(bundle.screenshotPng()) > 0);
        assertTrue(Files.size(bundle.replayGif()) > 0);
    }

    @Test
    void macroMoveCardExpandsAndCollapsesAtomicSteps() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.setContent("""
                <section id="tab-replay">
                  <div class="replay-rule-card replay-macro-card">
                    <strong>Makrozug: macro_demo</strong>
                    <details>
                      <summary>Atomare Replay-Schritte anzeigen</summary>
                      <ol class="replay-macro-steps"><li>atomic step</li></ol>
                    </details>
                  </div>
                </section>
                """);

            assertTrue(page.locator(".replay-macro-card").isVisible());
            assertTrue(page.locator(".replay-macro-steps").isHidden());
            page.locator("summary").click();
            assertTrue(page.locator(".replay-macro-steps").isVisible());
            page.locator("summary").click();
            assertTrue(page.locator(".replay-macro-steps").isHidden());
        }
    }

    private static DeterministicDiscoveryExperimentRunner.DiscoveryReport sampleReport() {
        return new DeterministicDiscoveryExperimentRunner.DiscoveryReport(
            List.of(new DeterministicDiscoveryExperimentRunner.SeedRunReport(
                new SeedExpression("identity-binomial-1", "(x + 1)^2", "known-identity", "binomial",
                    List.of("scientific"), List.of("x != 0")),
                true,
                "binomial reproduced",
                List.of("hyp-binomial"),
                List.of(),
                List.of("(x + 1)^2", "(x + 1) * (x + 1)", "x^2 + 2*x + 1"),
                12L,
                1024L
            )),
            new DeterministicDiscoveryExperimentRunner.DiscoveryMetrics(1, 1, 1, 0, 12L, 1024L),
            13L
        );
    }
}
