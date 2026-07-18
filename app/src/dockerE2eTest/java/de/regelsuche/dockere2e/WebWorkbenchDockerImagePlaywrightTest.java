package de.regelsuche.dockere2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Playwright-based Docker-image tests that verify KaTeX actually renders
 * mathematical expressions in the real container (not just that the JS files
 * are served).
 *
 * <p>The definitive check is finding at least one {@code .katex} element in the
 * DOM after clicking the Binomial demo button. Raw {@code $...$} strings and
 * {@code math-fallback} elements indicate KaTeX did not run.</p>
 *
 * <p>These tests skip when Docker is not available or when Playwright browsers
 * have not been installed ({@code ./gradlew installPlaywrightBrowsers}).</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class WebWorkbenchDockerImagePlaywrightTest {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(WebWorkbenchDockerImagePlaywrightTest.class);

    private static final String PROJECT_ROOT =
        System.getProperty("regelsuche.projectRoot",
            Path.of("").toAbsolutePath().toString());

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> CONTAINER =
        new GenericContainer<>(
            new ImageFromDockerfile()
                .withFileFromPath(".", Path.of(PROJECT_ROOT)))
            // Rendering and browser behaviour do not require durable state. Keep
            // the image test independent of anonymous-volume ownership details.
            .withEnv("REGELSUCHE_PERSISTENCE_MODE", "IN_MEMORY")
            .withLogConsumer(new Slf4jLogConsumer(LOG))
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/").forStatusCode(200));

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void startPlaywright() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
        } catch (Exception e) {
            assumeTrue(false,
                "Playwright browsers not installed – run ./gradlew installPlaywrightBrowsers: " + e.getMessage());
        }
    }

    @AfterAll
    static void stopPlaywright() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    private String baseUrl() {
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(8080);
    }

    @Test
    void katexRendersInBinomialDemo() {
        assumeTrue(browser != null, "Browser not initialized – skipping");

        try (Page page = browser.newPage()) {
            page.navigate(baseUrl() + "/");
            page.waitForLoadState();

            page.waitForSelector("button.demo-button[data-demo='binomial']",
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
            page.click("button.demo-button[data-demo='binomial']");

            page.waitForSelector("#demoStatus.ok",
                new Page.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(60_000));

            page.waitForSelector(".katex",
                new Page.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(30_000));

            List<ElementHandle> katexElements = page.querySelectorAll(".katex");
            assertTrue(katexElements.size() > 0,
                "Expected at least one .katex element rendered by KaTeX, found none. " +
                "This indicates KaTeX did not load or run.");

            List<ElementHandle> fallbackElements = page.querySelectorAll(".math-fallback");
            assertFalse(fallbackElements.stream()
                .anyMatch(el -> {
                    try {
                        return el.isVisible();
                    } catch (Exception e) {
                        return false;
                    }
                }),
                "Found visible .math-fallback elements – KaTeX rendering did not complete.");
        }
    }

    @Test
    void pageLoadsWithoutJsErrors() {
        assumeTrue(browser != null, "Browser not initialized – skipping");

        try (Page page = browser.newPage()) {
            var errors = new java.util.ArrayList<String>();
            page.onConsoleMessage(msg -> {
                if ("error".equals(msg.type())) {
                    errors.add(msg.text());
                }
            });

            page.navigate(baseUrl() + "/");
            page.waitForLoadState();

            List<String> significantErrors = errors.stream()
                .filter(e -> !e.contains("favicon.ico"))
                .toList();

            assertTrue(significantErrors.isEmpty(),
                "Unexpected JS console errors: " + significantErrors);
        }
    }
}
