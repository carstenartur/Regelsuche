package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Visual-hygiene regression test for the demo-summary panel.
 *
 * <p>The demo banner, "Treffer (selectedPath)" row, best-move block and
 * identities list used to render mathematical expressions as raw ASCII
 * (literal {@code ^}, {@code *}, …) directly into the DOM — which made
 * the {@code docs/assets/screenshots/binomial-graph.png} reference (and
 * every demo's headline section) look like a debug log instead of
 * mathematics.</p>
 *
 * <p>This test guards against any future regression of that pattern by
 * running every primary demo and asserting that the {@code #demoSummary}
 * subtree contains no ASCII caret {@code ^} <em>outside</em> {@code <code>}
 * elements. ASCII inside {@code <code>} is intentionally retained as a
 * source / screen-reader-friendly view of the original expression and is
 * therefore the only place {@code ^} is allowed to appear.</p>
 */
class DemoSummaryAsciiHygieneTest {

    private static RegelsucheAppEnvironment app;
    private static Playwright playwright;
    private static Browser browser;

    private BrowserContext context;
    private Page page;

    @BeforeAll
    static void boot() throws IOException {
        app = new RegelsucheAppEnvironment();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void shutdown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (app != null) app.close();
    }

    @BeforeEach
    void openPage() {
        context = browser.newContext(new Browser.NewContextOptions()
            .setViewportSize(1400, 900));
        page = context.newPage();
        page.navigate(app.baseUrl());
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    @AfterEach
    void closePage() {
        if (page != null) page.close();
        if (context != null) context.close();
    }

    /**
     * Every primary demo's headline summary must typeset its mathematical
     * expressions via KaTeX. The only place an ASCII {@code ^} is allowed
     * to remain is inside a {@code <code>} block (kept on purpose as a
     * source / screen-reader-friendly view of the raw expression).
     */
    @ParameterizedTest(name = "demo {0}: #demoSummary has no ASCII ^ outside <code>")
    @ValueSource(strings = {
        "binomial",
        "rational",
        "trigonometry",
        "polynomial-expansion",
        "math-equation",
        "math-inequality",
        "math-derivative",
        "math-matrix"
    })
    @DisplayName("Demo summaries never expose ASCII ^ outside <code> blocks")
    void demoSummaryHasNoAsciiCaretOutsideCodeBlocks(String demoId) {
        page.locator(".demo-button[data-demo='" + demoId + "']").click();
        // Wait until the summary has been populated by the demo run.
        page.waitForFunction(
            "() => document.querySelector('#demoSummary') "
                + "&& document.querySelector('#demoSummary').innerHTML.length > 50",
            null, new Page.WaitForFunctionOptions().setTimeout(60_000));
        // Walk #demoSummary in the page, collecting text content from every
        // node whose closest <code>-ancestor is null. KaTeX-rendered math
        // (`.katex`) is also excluded because its rendered glyphs share
        // codepoints (U+02C6 etc.) that we don't want to scan.
        Object asciiOutside = page.evaluate(
            "() => {"
                + " const root = document.querySelector('#demoSummary');"
                + " if (!root) return '';"
                + " const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);"
                + " let acc = '';"
                + " let node;"
                + " while ((node = walker.nextNode())) {"
                + "   let p = node.parentElement;"
                + "   let skip = false;"
                + "   while (p && p !== root) {"
                + "     if (p.tagName === 'CODE' || (p.classList && p.classList.contains('katex'))) {"
                + "       skip = true; break;"
                + "     }"
                + "     p = p.parentElement;"
                + "   }"
                + "   if (!skip) acc += node.textContent;"
                + " }"
                + " return acc;"
                + "}");
        String text = String.valueOf(asciiOutside);
        assertTrue(!text.contains("^"),
            "demo " + demoId + ": ASCII '^' must not appear in #demoSummary "
                + "outside <code> blocks (found in: '"
                + text.replace('\n', ' ').trim() + "')");
    }

    /**
     * The headline banner must use the KaTeX rendering pipeline, not raw
     * ASCII or un-typeset {@code .math} placeholders. This is intentionally
     * stricter than "a math span exists": it waits for the rendered
     * {@code .katex} subtree and rejects visible fallback blocks.
     */
    @ParameterizedTest(name = "demo {0}: banner contains a KaTeX-typeset math span")
    @ValueSource(strings = {
        "binomial",
        "rational",
        "trigonometry",
        "polynomial-expansion",
        "math-equation",
        "math-inequality",
        "math-derivative",
        "math-matrix"
    })
    @DisplayName("Demo banner is KaTeX-typeset, not ASCII")
    void demoBannerUsesKatex(String demoId) {
        page.locator(".demo-button[data-demo='" + demoId + "']").click();
        page.waitForFunction(
            "() => document.querySelector('#demoSummary') "
                + "&& document.querySelector('#demoSummary').innerHTML.length > 50",
            null, new Page.WaitForFunctionOptions().setTimeout(60_000));
        page.waitForSelector("#demoSummary .demo-banner .katex",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(15_000));
        int mathNodes = ((Number) page.evaluate(
            "() => document.querySelectorAll('#demoSummary .demo-banner .katex').length")).intValue();
        assertTrue(mathNodes > 0,
            "demo " + demoId + ": banner must contain at least one KaTeX-typeset "
                + "math span (found " + mathNodes + ")");
        int visibleFallbacks = ((Number) page.evaluate(
            "() => Array.from(document.querySelectorAll('#demoSummary .demo-banner .math-fallback'))"
                + ".filter((el) => el.offsetParent !== null).length")).intValue();
        assertTrue(visibleFallbacks == 0,
            "demo " + demoId + ": banner must not show math fallback blocks when KaTeX is available");
    }

    @DisplayName("Search graph overlay renders node math through KaTeX")
    @org.junit.jupiter.api.Test
    void graphOverlayUsesKatexNodeLabels() {
        page.locator(".demo-button[data-demo='binomial']").click();
        page.waitForFunction(
            "() => document.querySelector('#demoSummary') "
                + "&& document.querySelector('#demoSummary').innerHTML.length > 50",
            null, new Page.WaitForFunctionOptions().setTimeout(60_000));
        page.waitForFunction(
            "() => typeof window.cytoscape === 'function' || window.__cytoscapeFailed === true",
            null, new Page.WaitForFunctionOptions().setTimeout(15_000));
        Boolean cytoscapeFailed = (Boolean) page.evaluate("() => window.__cytoscapeFailed === true");
        assertTrue(!cytoscapeFailed, "vendored Cytoscape must load for graph-overlay rendering");
        page.locator(".tab[data-tab='graph']").click();
        page.locator("#graphInteractive").check();
        page.locator("#reloadGraph").click();
        page.waitForSelector("#graphCanvas .graph-overlay-layer .graph-node-math .katex",
            new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(15_000));
        int overlayKatexNodes = ((Number) page.evaluate(
            "() => document.querySelectorAll('#graphCanvas .graph-overlay-layer "
                + ".graph-node-math .katex').length")).intValue();
        assertTrue(overlayKatexNodes > 0,
            "graph overlay must render mathematical node labels as KaTeX");
    }
}
