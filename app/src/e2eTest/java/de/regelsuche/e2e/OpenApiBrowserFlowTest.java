package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiBrowserFlowTest {
    private static RegelsucheAppEnvironment app;
    private static Playwright playwright;
    private static Browser browser;

    private BrowserContext context;
    private Page page;
    private List<String> externalRequests;

    @BeforeAll
    static void boot() throws IOException {
        app = new RegelsucheAppEnvironment();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void shutdown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (app != null) app.close();
    }

    @BeforeEach
    void open() {
        externalRequests = new ArrayList<>();
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1440, 1000));
        page = context.newPage();
        page.onRequest(request -> {
            URI uri = URI.create(request.url());
            String host = uri.getHost();
            if (host != null && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                externalRequests.add(request.url());
            }
        });
        page.navigate(app.baseUrl() + "/static/openapi/index.html");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.waitForFunction("() => document.querySelector('#swagger-ui')?.dataset.state === 'ready'");
    }

    @AfterEach
    void close() {
        if (page != null) page.close();
        if (context != null) context.close();
    }

    @Test
    @DisplayName("Official Swagger UI renders the complete API locally without external requests")
    void rendersOfflineSwaggerUiAndOpensProofJobContract() {
        assertTrue(externalRequests.isEmpty(), "Swagger UI must stay offline: " + externalRequests);
        assertEquals(1, page.locator("#swagger-ui[data-state='ready']").count());
        assertTrue(page.locator(".opblock").count() >= 45);
        assertTrue(page.locator(".opblock-tag").allTextContents().stream()
            .anyMatch(text -> text.contains("Search")));
        assertTrue(page.locator(".opblock-tag").allTextContents().stream()
            .anyMatch(text -> text.contains("Proof Jobs")));
        assertTrue(page.locator(".opblock-tag").allTextContents().stream()
            .anyMatch(text -> text.contains("Rule Radar")));

        Locator filter = page.locator(".operation-filter-input");
        assertEquals(1, filter.count());
        filter.fill("Proof Jobs");
        page.waitForFunction("() => document.querySelectorAll('.opblock-tag-section:not([style*=\"display: none\"])').length >= 1");
        assertEquals(1, page.locator(".opblock-tag-section:visible").count());
        assertTrue(page.locator(".opblock-tag-section:visible").innerText().contains("Proof Jobs"));
        filter.fill("");

        Locator submitJob = page.locator(".opblock")
            .filter(new Locator.FilterOptions().setHasText("submitProofJob"));
        assertEquals(1, submitJob.count());
        assertTrue(submitJob.innerText().contains("/api/proof/jobs"));
        submitJob.locator(".opblock-summary").click();
        page.waitForFunction("() => Array.from(document.querySelectorAll('.opblock'))"
            + ".some(block => block.textContent.includes('submitProofJob')"
            + " && block.querySelector('.opblock-body'))");
        String contract = submitJob.innerText();
        assertTrue(contract.contains("leftPattern"));
        assertTrue(contract.contains("201"));
        assertTrue(contract.contains("400"));
    }
}
