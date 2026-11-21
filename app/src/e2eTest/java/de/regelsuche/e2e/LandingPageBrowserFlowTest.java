package de.regelsuche.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import de.regelsuche.json.JsonReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Browser-driven tests for the simplified landing-page UX from PR #16
 * follow-up §3: tabs must stay hidden until the first search starts; goal
 * selection must round-trip into the search request; clicking a demo also
 * counts as starting a search.
 */
class LandingPageBrowserFlowTest {

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
    static void teardown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (app != null) app.close();
    }

    @BeforeEach
    void openContext() {
        context = browser.newContext(new Browser.NewContextOptions()
            .setViewportSize(1400, 900));
        page = context.newPage();
        page.navigate(app.baseUrl());
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    @AfterEach
    void close() {
        if (page != null) page.close();
        if (context != null) context.close();
    }

    @Test
    @DisplayName("Landing page presents a single primary entry flow")
    void landingPageShowsSimplePrimaryFlow() {
        // The workbench entry tab is always visible and active by default.
        assertTrue(page.locator(".tab[data-tab='workbench']").isVisible(),
            "the workbench entry tab must be visible on landing");
        assertTrue(page.locator(".tab[data-tab='workbench'].active").count() >= 1,
            "the workbench tab must be the active default");
        // The primary flow — input + goal + search button — is reachable
        // without first navigating anywhere else.
        assertTrue(page.locator("#searchForm").isVisible(),
            "the main search form must be on the landing page");
        // The goal dropdown surfaces the documented TransformationGoal options.
        String goalHtml = page.locator("#searchForm select[name='goal']")
            .innerHTML();
        for (String option : new String[] {
            "SIMPLIFY", "FACTORIZE", "TEACHING_FRIENDLY",
            "PROOF_FRIENDLY", "NUMERICALLY_STABLE"
        }) {
            assertTrue(goalHtml.contains(option),
                "goal dropdown must offer the " + option + " option");
        }
    }

    @Test
    @DisplayName("Tabs other than the entry one are hidden before the first search")
    void tabsHiddenBeforeFirstSearch() {
        assertEquals("true", page.locator("body").getAttribute("data-pre-search"),
            "body must start in pre-search state");
        // CSS hides downstream tabs; Playwright .isVisible() respects display:none.
        assertFalse(page.locator(".tab[data-tab='graph']").isVisible(),
            "graph tab must be hidden before the first search");
        assertFalse(page.locator(".tab[data-tab='replay']").isVisible(),
            "replay tab must be hidden before the first search");
        assertFalse(page.locator(".tab[data-tab='proofJobs']").isVisible(),
            "proof-jobs tab must be hidden before the first search");
    }

    @Test
    @DisplayName("Tabs become visible after a demo / search is started")
    void tabsVisibleAfterSearch() {
        // Click the binomial demo; this counts as starting the first search.
        page.locator(".demo-button[data-demo='binomial']").click();
        page.waitForFunction(
            "() => document.body.dataset.preSearch === 'false'",
            null, new Page.WaitForFunctionOptions().setTimeout(60_000));
        assertTrue(page.locator(".tab[data-tab='graph']").isVisible(),
            "graph tab must reveal after the first search");
        assertTrue(page.locator(".tab[data-tab='replay']").isVisible(),
            "replay tab must reveal after the first search");
        assertTrue(page.locator(".tab[data-tab='proofJobs']").isVisible(),
            "proof-jobs tab must reveal after the first search");
        assertTrue(page.locator(".tab[data-tab='exports']").isVisible(),
            "exports tab must reveal after the first search");
    }

    @Test
    @DisplayName("Goal selection is submitted with the search request")
    void goalSelectionIsSubmittedWithSearch() {
        // Capture the POST body to /api/search to assert the goal is forwarded.
        StringBuilder captured = new StringBuilder();
        page.route("**/api/search", route -> {
            captured.append(route.request().postData());
            route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setStatus(200)
                .setContentType("application/json")
                .setBody("{\"submitted\":true,\"acceptedAt\":\"2024-01-01T00:00:00Z\"}"));
        });
        page.locator("#searchForm input[name='expression']").fill("(x+1)^2");
        page.locator("#searchForm select[name='goal']").selectOption("TEACHING_FRIENDLY");
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction(
            "() => document.body.dataset.preSearch === 'false'",
            null, new Page.WaitForFunctionOptions().setTimeout(10_000));
        assertTrue(captured.toString().contains("TEACHING_FRIENDLY"),
            "search request must include the selected goal: " + captured);
    }

    @Test
    void visualFormulaEditsAndTextUseTheSearchParserBeforeSubmission() {
        var input = page.locator("#searchForm input[name='expression']");
        page.locator("#formulaTools summary").click();
        input.fill("-x + y");
        page.locator("#previewFormula").click();
        var preview = page.locator("#formulaPreview");
        page.waitForFunction("() => document.querySelector('#formulaPreview math') !== null");
        assertEquals("ADD(SUB(0, x), y)", preview.locator("code").textContent());
        input.evaluate("node => node.setSelectionRange(1, 2)");
        page.locator("button[data-formula='square']").click();
        assertEquals("-(x)^2 + y", input.inputValue());
        assertEquals(0, preview.locator("math").count(), "an edit must invalidate the old AST");
        page.locator("#previewFormula").click();
        page.waitForFunction("() => document.querySelector('#formulaPreview msup') !== null");
        assertEquals("ADD(SUB(0, POW(x, 2)), y)", preview.locator("code").textContent());
        page.locator("#searchForm select[name='type']").selectOption("EQUATION");
        input.fill("x/(a+b) = z");
        page.locator("#previewFormula").click();
        page.waitForFunction("() => document.querySelector('#formulaPreview mfrac') !== null");
        assertEquals("EQUATION(DIV(x, ADD(a, b)), z)", preview.locator("code").textContent());
        String submitted = page.waitForRequest("**/api/search",
            () -> page.locator("#searchForm button[type='submit']").click()).postData();
        assertTrue(submitted.contains("x/(a+b) = z"), submitted);
        page.locator("#searchForm select[name='type']").selectOption("TERM");
        input.fill("x/(");
        page.locator("#previewFormula").click();
        page.waitForFunction("() => document.querySelector('#formulaPreview').textContent.includes('position')");
        assertEquals(0, preview.locator("math").count());
        var range = preview.locator("button");
        range.click();
        assertEquals(3, input.evaluate("node => node.selectionStart"));
        assertEquals(3, input.evaluate("node => node.selectionEnd"));

        page.locator("#searchForm select[name='type']").selectOption("EQUATION");
        input.fill("  α = 2💡 + β");
        page.locator("#previewFormula").click();
        range.click();
        assertEquals("💡", input.evaluate("node => node.value.slice(node.selectionStart, node.selectionEnd)"));
        assertEquals(7, input.evaluate("node => node.selectionStart"));
        assertEquals(9, input.evaluate("node => node.selectionEnd"));

        page.locator("#searchForm select[name='type']").selectOption("TERM");
        input.fill("2x");
        page.locator("#previewFormula").click();
        range.click();
        assertEquals("x", input.evaluate("node => node.value.slice(node.selectionStart, node.selectionEnd)"));
        input.fill("2*x");
        page.locator("#previewFormula").click();
        page.waitForFunction("() => document.querySelector('#formulaPreview math') !== null");
        assertEquals("MUL(2, x)", preview.locator("code").textContent());

        // The caller may change input while the production parser response is in flight.
        page.evaluate("""
            const originalFetch = window.fetch.bind(window);
            window.fetch = async (...args) => {
                const response = await originalFetch(...args);
                if (args[0] === '/api/inspect/formula') {
                    await new Promise(resolve => { window.releaseFormulaResponse = resolve; });
                }
                return response;
            };
            """);
        input.fill("x + 1");
        page.locator("#previewFormula").click();
        page.waitForFunction("() => typeof window.releaseFormulaResponse === 'function'");
        page.locator("#loadExample-trig").click();
        page.evaluate("async () => { window.releaseFormulaResponse(); await new Promise(resolve => requestAnimationFrame(resolve)); }");
        assertEquals("", preview.textContent());
        assertEquals("sin(x)^2 + cos(x)^2", input.inputValue());

        var tooLong = page.request().post(app.baseUrl() + "/api/inspect/formula",
            com.microsoft.playwright.options.RequestOptions.create().setData(java.util.Map.of(
                "type", "TERM", "expression", "x".repeat(257))));
        assertEquals(400, tooLong.status());
        assertTrue(tooLong.text().contains("256"));
    }

    @Test
    void editsTheSecondRenderedOccurrenceWithTheKeyboardAndSubmitsTheExactSource() {
        var input = page.locator("#searchForm input[name='expression']");
        page.locator("#formulaTools summary").click();
        page.locator("#searchForm select[name='type']").selectOption("EQUATION");
        page.locator("#searchForm select[name='profile']").selectOption("FAST_SIMPLIFY");
        input.fill("f(x, x) = -(0.50)^2");
        page.locator("#previewFormula").click();

        var part = page.getByLabel("Teilausdruck wählen");
        var replacement = page.getByLabel("Teilausdruck ersetzen");
        assertThat(part).isVisible();
        part.press("ArrowDown");
        part.press("ArrowDown");
        part.press("ArrowDown");
        assertEquals("5:6", part.inputValue());
        assertEquals("x", replacement.inputValue());
        assertTrue(page.locator("math [data-formula-part='5:6'].is-selected").isVisible());

        replacement.fill("y + 1");
        replacement.press("Enter");
        var ast = page.locator("#formulaPreview code");
        String expected = "f(x, (y + 1)) = -(0.50)^2";
        assertThat(input).hasValue(expected);
        assertEquals("EQUATION(f(x, ADD(y, 1)), SUB(0, POW(1/2, 2)))", ast.textContent());
        var response = page.waitForResponse("**/api/search",
            () -> page.locator("#searchForm button[type='submit']").click());
        assertEquals(200, response.status());
        var payload = new JsonReader(response.request().postData()).readObject();
        assertEquals(expected, payload.get("expression"));
        assertEquals("EQUATION", payload.get("type"));
        assertEquals(expected, new JsonReader(response.text()).readObject().get("expression"));
    }

    @Test
    void selectsInMathMlAndRejectsInvalidOrMultiPartReplacement() {
        var input = page.locator("#searchForm input[name='expression']");
        page.locator("#formulaTools summary").click();
        input.fill("f(x, x)");
        page.locator("#previewFormula").click();
        page.locator("math [data-formula-part='5:6']").click();
        assertEquals("5:6", page.getByLabel("Teilausdruck wählen").inputValue());
        var replacement = page.getByLabel("Teilausdruck ersetzen");
        assertThat(replacement).isFocused();
        var apply = page.getByRole(AriaRole.BUTTON,
            new Page.GetByRoleOptions().setName("Änderung prüfen und übernehmen"));
        var status = page.locator("#formulaStatus");

        replacement.fill("y +");
        apply.click();
        assertThat(status).containsText("Änderung nicht übernommen");
        assertThat(input).hasValue("f(x, x)");
        assertThat(page.locator("#formulaPreview code")).hasText("f(x, x)");
        replacement.fill("y), (z");
        apply.click();
        assertThat(status).containsText("einen einzelnen Teilausdruck");
        assertThat(input).hasValue("f(x, x)");
        replacement.fill("y)+z+(w");
        apply.click();
        // The surrounding parentheses can balance an invalid draft inside a function argument.
        assertThat(status).containsText("Änderung nicht übernommen");
        assertThat(input).hasValue("f(x, x)");

        input.fill("2 * (0.50 + x)");
        assertFalse(replacement.isVisible());
        assertEquals(0, page.locator("#formulaPreview math").count());
        page.locator("#previewFormula").click();
        assertThat(page.locator("#formulaPreview code")).hasText("MUL(2, ADD(1/2, x))");
        page.locator("math [data-formula-part='0:1']").click();
        replacement.fill("a + b");
        apply.click();
        assertThat(input).hasValue("(a + b) * (0.50 + x)");
        assertThat(page.locator("#formulaPreview code")).hasText("MUL(ADD(a, b), ADD(1/2, x))");
    }

    @Test
    void emptyDraftRejectionPreservesTheFormulaAndAllowsAValidRetry() {
        String original = "f(x, x)";
        String draft = "y)+z+(w";
        var input = page.locator("#searchForm input[name='expression']");
        var ast = page.locator("#formulaPreview code");
        page.locator("#formulaTools summary").click();
        input.fill(original);
        page.locator("#previewFormula").click();
        page.locator("math [data-formula-part='5:6']").click();

        var inspected = new ArrayList<String>();
        page.route("**/api/inspect/formula", route -> {
            String expression = (String) new JsonReader(route.request().postData()).readObject().get("expression");
            inspected.add(expression);
            if (expression.equals(draft)) {
                route.fulfill(new Route.FulfillOptions().setStatus(400).setContentType("text/plain").setBody(""));
            } else {
                route.resume();
            }
        });
        var replacement = page.getByLabel("Teilausdruck ersetzen");
        replacement.fill(draft);
        replacement.press("Enter");
        page.waitForFunction("() => document.getElementById('formulaStatus').textContent.includes('übernommen')");
        assertEquals(List.of(draft), inspected, "The rejected draft must not trigger full-proposal inspection");
        assertThat(page.locator("#formulaStatus")).hasText("Änderung nicht übernommen. Der Parser hat die Eingabe abgelehnt.");
        assertThat(input).hasValue(original);
        assertThat(ast).hasText(original);

        replacement.fill("y");
        replacement.press("Enter");
        assertThat(input).hasValue("f(x, (y))");
        assertThat(ast).hasText("f(x, y)");
        assertEquals(List.of(draft, "y", "f(x, (y))"), inspected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DIRECT_V1", "SAFE_PREPARATION_V4"})
    void visuallyEditedSystemUsesGlobalOccurrenceOffsetsAndTheSelectedRuntimeProfile(String profile) {
        page.locator("#runtimeProfile").selectOption(profile);
        page.locator("[name=runtimeShape]").selectOption("SYSTEM");
        var input = page.locator("#searchForm [name=expression]");
        input.fill("x+y=2; x-y=0");
        page.locator("#formulaTools summary").click();
        page.locator("#previewFormula").click();
        page.locator("math [data-formula-part='7:8']").click();
        page.getByLabel("Teilausdruck ersetzen").fill("2*x");
        page.getByLabel("Teilausdruck ersetzen").press("Enter");
        assertThat(input).hasValue("x+y=2; (2*x)-y=0");
        var response = page.waitForResponse(r -> r.url().endsWith("/api/search") && r.request().method().equals("POST"),
            () -> page.locator("#searchForm button[type='submit']").click());
        assertTrue(response.ok(), response::text);
        assertTrue(response.text().contains("(2*x)-y=0"));
        assertTrue(response.text().contains(profile));
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "type", "profile", "draft"})
    void pendingVisualEditCannotOverwriteANewerContext(String change) {
        var input = page.locator("#searchForm input[name='expression']");
        var inputType = page.locator("#searchForm select[name='type']");
        var profile = page.locator("#runtimeProfile");
        page.locator("#formulaTools summary").click();
        input.fill("f(x, x)");
        page.locator("#previewFormula").click();
        page.locator("math [data-formula-part='5:6']").click();
        var replacement = page.getByLabel("Teilausdruck ersetzen");
        replacement.fill("y");
        // Hold the actual server response after it has been delivered to fetch.
        page.evaluate("""
            const originalFetch = window.fetch.bind(window);
            window.fetch = async (...args) => {
                const response = await originalFetch(...args);
                if (args[0] === '/api/inspect/formula' && JSON.parse(args[1].body).expression === 'f(x, (y))') {
                    await new Promise(resolve => { window.releaseVisualEdit = resolve; });
                }
                return response;
            };
            """);
        replacement.press("Enter");
        page.waitForFunction("() => typeof window.releaseVisualEdit === 'function'");
        switch (change) {
            case "source" -> input.fill("z^2");
            case "type" -> {
                inputType.selectOption("EQUATION");
                inputType.selectOption("TERM");
            }
            case "profile" -> {
                profile.selectOption("DIRECT_V1");
                assertTrue(page.locator("#formulaTools").isVisible());
                profile.selectOption("");
            }
            case "draft" -> replacement.fill("new_draft");
            default -> throw new AssertionError(change);
        }
        page.evaluate("""
            async () => {
                window.releaseVisualEdit();
                await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            }
            """);
        assertThat(input).hasValue(change.equals("source") ? "z^2" : "f(x, x)");
        if (change.equals("draft")) {
            assertThat(replacement).hasValue("new_draft");
            assertThat(page.locator("#formulaPreview math")).isVisible();
        } else {
            assertFalse(replacement.isVisible());
            assertEquals(0, page.locator("#formulaPreview math").count());
        }
        assertEquals("", page.locator("#formulaStatus").textContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"z", "z +"})
    void searchWaitsUntilFormulaValidationHasFinished(String draft) {
        boolean valid = draft.equals("z");
        String original = "x + 1";
        String edited = "(z) + 1";
        var input = page.locator("#searchForm input[name='expression']");
        var submit = page.locator("#searchForm button[type='submit']");
        page.locator("#formulaTools summary").click();
        page.locator("#searchForm select[name='profile']").selectOption("FAST_SIMPLIFY");
        input.fill(original);
        page.locator("#previewFormula").click();
        page.locator("math [data-formula-part='0:1']").click();
        var replacement = page.getByLabel("Teilausdruck ersetzen");
        replacement.fill(draft);

        var outgoingSearches = new ArrayList<Object>();
        page.onRequest(request -> {
            if (request.url().endsWith("/api/search") && request.method().equals("POST")) {
                outgoingSearches.add(new JsonReader(request.postData()).readObject().get("expression"));
            }
        });
        page.evaluate("""
            heldExpression => {
                const originalFetch = window.fetch.bind(window);
                window.fetch = async (...args) => {
                    const response = await originalFetch(...args);
                    if (args[0] === '/api/inspect/formula' && JSON.parse(args[1].body).expression === heldExpression) {
                        await new Promise(resolve => { window.releaseSearchGuardProbe = resolve; });
                    }
                    return response;
                };
            }
            """, valid ? edited : draft);
        replacement.press("Enter");
        page.waitForFunction("() => typeof window.releaseSearchGuardProbe === 'function'");

        submit.click();
        input.press("Enter");
        page.evaluate("async () => { await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))); }");
        assertEquals(List.of(), outgoingSearches, "Pointer and keyboard must not submit the old expression");
        var status = page.locator("#formulaStatus");
        assertThat(status).containsText("Änderung wird noch geprüft");

        page.evaluate("() => window.releaseSearchGuardProbe()");
        assertThat(status).containsText(valid ? "Änderung übernommen" : "Änderung nicht übernommen");
        assertThat(input).hasValue(valid ? edited : original);
        assertEquals(List.of(), outgoingSearches, "A blocked submit must stay blocked");

        var result = page.waitForResponse("**/api/search", () -> {
            if (valid) submit.click();
            else input.press("Enter");
        });
        assertEquals(200, result.status());
        assertEquals(List.of(valid ? edited : original), outgoingSearches);
    }

    @Test
    void editsAnOccurrenceAtTheSourceLimitWithoutChangingEnclosingPrecedence() {
        var input = page.locator("#searchForm input[name='expression']");
        page.locator("#formulaTools summary").click();
        String prefix = "f(" + "a".repeat(251) + ",";
        input.fill(prefix + "x)");
        page.locator("#previewFormula").click();
        var part = page.getByLabel("Teilausdruck wählen");
        assertThat(part).isVisible();
        part.press("End");
        var replacement = page.getByLabel("Teilausdruck ersetzen");
        assertThat(replacement).hasValue("x");
        replacement.fill("y");
        replacement.press("Enter");
        assertThat(input).hasValue(prefix + "y)");
        assertThat(page.locator("#formulaPreview code")).hasText("f(" + "a".repeat(251) + ", y)");

        String product = "x * " + "a".repeat(248);
        input.fill(product);
        page.locator("#previewFormula").click();
        page.locator("math [data-formula-part='0:1']").click();
        replacement.fill("y + z");
        replacement.press("Enter");
        assertThat(page.locator("#formulaStatus")).containsText("braucht Klammern");
        assertThat(input).hasValue(product);
        assertThat(page.locator("#formulaPreview code")).hasText("MUL(x, " + "a".repeat(248) + ")");
    }

    @Test
    void formulaShortcutUsesTheOccurrenceSelectedInMathMl() {
        var input = page.locator("#searchForm input[name='expression']");
        page.locator("#formulaTools summary").click();
        input.fill("x^4 + 4*y^4");
        input.evaluate("node => node.setSelectionRange(0, 0)");
        page.locator("#previewFormula").click();
        page.locator("math [data-formula-part='6:11']")
            .getByText("*", new com.microsoft.playwright.Locator.GetByTextOptions().setExact(true)).click();
        assertThat(page.getByLabel("Teilausdruck ersetzen")).isFocused();

        page.locator("button[data-formula='square']").click();
        assertThat(input).hasValue("x^4 + (4*y^4)^2");
    }

    @Test
    void longFormulaScrollsWithinThePreviewOnANarrowViewport() {
        page.setViewportSize(390, 844);
        var input = page.locator("#searchForm input[name='expression']");
        page.locator("#formulaTools summary").click();
        String source = "f(" + "long_variable_name,".repeat(12) + "x)";
        input.fill(source);
        page.locator("#previewFormula").click();
        var part = page.getByLabel("Teilausdruck wählen");
        assertThat(part).isVisible();
        assertTrue((boolean) page.locator(".formula-display").evaluate("el => el.scrollWidth > el.clientWidth"));
        assertTrue((boolean) page.evaluate("() => document.documentElement.scrollWidth === window.innerWidth"));
        part.press("End");
        var replacement = page.getByLabel("Teilausdruck ersetzen");
        assertThat(replacement).hasValue("x");
        replacement.fill("z".repeat(32));
        replacement.press("Enter");
        assertThat(page.locator("#formulaStatus")).containsText("256 Zeichen");
        assertThat(input).hasValue(source);
        replacement.fill("0.50");
        replacement.press("Enter");
        assertThat(input).hasValue(source.substring(0, source.length() - 2) + "(0.50))");
    }

    @ParameterizedTest
    @ValueSource(ints = {1140, 390})
    void editsTheSophieGermainSummandInThePreview(int width) throws IOException {
        page.setViewportSize(width, width == 390 ? 844 : 900);
        var input = page.locator("#searchForm input[name='expression']");
        page.locator("#formulaTools summary").click();
        input.fill("x^4 + 4*y^4");
        page.locator("#previewFormula").click();
        var product = page.locator("math [data-formula-part='6:11']");
        product.getByText("*", new com.microsoft.playwright.Locator.GetByTextOptions().setExact(true)).click();
        assertThat(product).hasClass("is-selected");
        var replacement = page.getByLabel("Teilausdruck ersetzen");
        assertThat(replacement).hasValue("4*y^4");
        replacement.fill("4*(y^2)^2");
        String file = width == 390 ? "visual-formula-edit-mobile.png" : "visual-formula-edit.png";
        DocumentationScreenshots.capture(page, page.locator("#formulaInputArea"), file,
            "Select and edit the second summand of the Sophie-Germain input in the typeset preview",
            "x^4 + 4*y^4; selected 4*y^4; draft 4*(y^2)^2");
        replacement.press("Enter");
        assertThat(input).hasValue("x^4 + (4*(y^2)^2)");
        assertThat(page.locator("#formulaPreview code")).hasText("ADD(POW(x, 4), MUL(4, POW(POW(y, 2), 2)))");
    }

    @Test
    void learningEntryOpensAnInspectableRecordedComparison() {
        page.setViewportSize(390, 844);
        page.locator("#workflowLearn").click();
        page.locator("#openRecordedLearningComparison").press("Enter");
        page.waitForSelector("#qualification");
        assertTrue(page.locator("#qualification").textContent().contains("423 → 32"));
        page.locator("#qualification .case-group > summary").first().click();
        var example = page.locator("#qualificationPositives details").first();
        example.locator("summary").click();
        assertTrue(example.textContent().contains("26 → 3"));
        assertTrue(example.textContent().contains("REACHED"));
        page.goBack();
        assertTrue(page.locator("#tab-compare").isVisible());
    }

    @Test
    void primaryTasksAndKeyboardNavigationKeepToolsReachable() {
        assertTrue(page.locator("#workflowExplore").isVisible());
        assertTrue(page.locator("#workflowLearn").isVisible());
        assertEquals("/static/pair-reductions.html", page.locator("#workflowOptimize").getAttribute("href"));
        page.locator("#workflowLearn").click();
        assertTrue(page.locator("#tab-compare").isVisible());
        assertFalse(page.locator("#workflowEntry").isVisible(),
            "task entry cards belong to the Workbench, not the comparison view");
        assertFalse(page.locator("#demoHero").isVisible());
        assertTrue(page.url().endsWith("#tab=compare"));
        page.locator(".tab[data-tab='compare']").focus();
        page.keyboard().press("ArrowRight");
        assertEquals("true", page.locator(".tab[data-tab='inventory']").getAttribute("aria-selected"));
        assertTrue(page.locator("#tab-inventory").isVisible());
        page.reload();
        page.waitForLoadState(LoadState.NETWORKIDLE);
        assertTrue(page.locator("#tab-inventory").isVisible(), "a copied tool URL must reopen the same view");
    }

    @Test
    void workflowEntryStaysInWorkbenchWhileDemoContextSurvivesToolNavigation() {
        assertTrue(page.locator("#workflowEntry").isVisible());
        page.locator(".demo-button[data-demo='binomial']").click();
        page.waitForFunction("() => window.__regelsucheDemoReady === true");
        String summary = page.locator("#demoSummary").textContent();
        assertFalse(summary.isBlank(), "a successful demo supplies context for its graph and replay");

        for (String tool : new String[] {"graph", "replay"}) {
            page.locator(".tab[data-tab='" + tool + "']").click();
            assertFalse(page.locator("#workflowEntry").isVisible(), tool);
            assertTrue(page.locator("#demoHero").isVisible(), tool);
            assertTrue(page.locator("#demoSummary").isVisible(), tool);
            assertEquals(summary, page.locator("#demoSummary").textContent(), tool);
            assertTrue(page.locator("#tab-" + tool).isVisible(), tool);
        }

        page.locator(".tab[data-tab='compare']").click();
        assertFalse(page.locator("#workflowEntry").isVisible());
        assertFalse(page.locator("#demoHero").isVisible());
        assertEquals(summary, page.locator("#demoSummary").textContent());

        page.locator(".tab[data-tab='workbench']").click();
        assertTrue(page.locator("#workflowEntry").isVisible());
        assertTrue(page.locator("#demoHero").isVisible());
        assertEquals(summary, page.locator("#demoSummary").textContent());
    }

    @Test
    void searchSummaryShowsConditionsWithoutUpgradingExampleEvidence() {
        page.route("**/api/search", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
            .setStatus(200).setContentType("application/json").setBody("""
                {"expression":"x/x","bestExpression":"1","successes":1,
                 "assumptions":["x != 0","<img src=x onerror=alert(1)>"],
                 "proofStatus":"VALIDATED_BY_EXAMPLES"}
                """)));
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction("() => !document.getElementById('searchResultSummary').hidden");
        String result = page.locator("#searchResultSummary").innerText();
        assertTrue(result.contains("x/x → 1"), result);
        assertTrue(result.contains("x != 0"), result);
        assertTrue(result.contains("beweist keine allgemeine Gültigkeit"), result);
        assertEquals(0, page.locator("#searchResultSummary img").count(), "server strings must stay inert text");
        assertFalse(page.locator("#searchResponseDetails").evaluate("node => node.open").equals(Boolean.TRUE));
        page.locator("#searchResultSummary [data-open-tab='paths']").click();
        assertTrue(page.locator("#tab-paths").isVisible());
    }

    @Test
    void searchBudgetAndTechnicalFailureRemainDistinctFromDisproof() {
        page.route("**/api/search", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
            .setStatus(200).setContentType("application/json").setBody("""
                {"expression":"x+x","bestExpression":null,"successes":0,"stopReason":"NODE_BUDGET",
                 "diagnostics":[{"ruleId":"broken","stage":"APPLY","message":"test failure"}]}
                """)));
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction("() => !document.getElementById('searchResultSummary').hidden");
        String result = page.locator("#searchResultSummary").innerText();
        assertTrue(result.contains("NODE_BUDGET"), result);
        assertTrue(result.contains("keine Angaben zu Voraussetzungen"), result);
        page.locator("#searchResultSummary details summary").click();
        assertTrue(page.locator("#searchResultSummary").innerText().contains("broken · APPLY · test failure"));
        page.unroute("**/api/search");
        page.route("**/api/search", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
            .setStatus(500).setContentType("text/plain").setBody("Executor unavailable")));
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction("() => document.getElementById('searchResultSummary').textContent.includes('Executor unavailable')");
        assertTrue(page.locator("#searchResultSummary").innerText().contains("kein mathematisches Gegenbeispiel"));
    }

    @Test
    void deepLinksLoadDataAndCanLeaveARetainedRun() {
        var requests = new AtomicInteger();
        page.route("**/api/inventory", route -> {
            requests.incrementAndGet();
            route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setStatus(200).setContentType("application/json").setBody("{\"rules\":[]}"));
        });
        page.navigate(app.baseUrl() + "#tab=inventory");
        page.waitForFunction("() => document.getElementById('inventoryList').dataset.loaded === '1'");
        assertEquals(1, requests.get(), "deep link must run the ordinary tab data loader");
        page.reload();
        page.waitForFunction("() => document.getElementById('inventoryList').dataset.loaded === '1'");
        assertEquals(2, requests.get(), "fresh navigation must load the requested view");
        page.evaluate("location.hash = '#run=invalid'");
        page.waitForFunction("() => document.body.classList.contains('retained-run')");
        page.evaluate("location.hash = '#tab=inventory'");
        page.waitForFunction("() => document.getElementById('tab-inventory').classList.contains('active')");
        assertFalse(page.locator("body").getAttribute("class").contains("retained-run"));
        assertTrue(page.locator("#tab-inventory").isVisible());
    }

    @Test
    void runtimeHeadlineUsesTheChosenCandidatesRequirements() {
        page.route("**/api/search", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
            .setStatus(200).setContentType("application/json").setBody("""
                {"schema":"regelsuche.safe-runtime-artifact/v1","evidence":{
                  "request":{"source":"log(a*b)","profile":"SAFE_PREPARATION_V4"},
                  "status":"CANDIDATES_FOUND","retainedAssumptions":[],"authority":{},
                  "outcomes":[{"status":"DIRECT","candidate":{"expression":"log(a)+log(b)",
                    "retainedAssumptions":["a > 0","b > 0"]}}]}}
                """)));
        page.locator("#runtimeProfile").selectOption("SAFE_PREPARATION_V4");
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction("() => !document.getElementById('searchResultSummary').hidden");
        String summary = page.locator("#searchResultSummary").innerText();
        assertTrue(summary.contains("a > 0") && summary.contains("b > 0"), summary);
        assertFalse(summary.contains("keine zusätzlichen Voraussetzungen"), summary);
    }

    @Test
    void supersededOrFailedPathLoadsRemainRetryable() {
        page.evaluate("""
            (() => {
              const originalFetch = window.fetch;
              window.pendingPaths = [];
              window.fetch = (url, options) => url === '/api/paths'
                ? new Promise(resolve => window.pendingPaths.push((data, status = 200) =>
                    resolve(new Response(JSON.stringify(data), {status, headers:{'Content-Type':'application/json'}}))))
                : url === '/api/search' ? Promise.resolve(new Response('current search failed', {status:500}))
                : originalFetch(url, options);
              window.RegelsucheWorkbench.activateTab('paths');
            })()
            """);
        page.waitForFunction("() => window.pendingPaths.length === 1");
        page.evaluate("window.RegelsucheWorkbench.activateTab('workbench')");
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction("() => document.getElementById('searchResultSummary').textContent.includes('current search failed')");
        page.evaluate("window.pendingPaths[0]({paths:[]})");
        page.evaluate("() => new Promise(requestAnimationFrame)");
        page.evaluate("window.RegelsucheWorkbench.activateTab('paths')");
        page.waitForFunction("() => window.pendingPaths.length === 2");
        page.evaluate("window.pendingPaths[1]({error:'paths unavailable'}, 500)");
        page.waitForFunction("() => document.getElementById('pathsList').textContent.includes('paths unavailable')");
        assertFalse("1".equals(page.locator("#pathsList").getAttribute("data-loaded")));
        page.evaluate("window.RegelsucheWorkbench.activateTab('workbench')");
        page.evaluate("window.RegelsucheWorkbench.activateTab('paths')");
        page.waitForFunction("() => window.pendingPaths.length === 3");
        page.evaluate("window.pendingPaths[2]({paths:[]})");
        page.waitForFunction("() => document.getElementById('pathsList').dataset.loaded === '1'");
        assertTrue(page.locator("#pathsList").innerText().contains("Keine Pfade"));
    }

    @Test
    void obsoleteSearchOrImportCannotOverwriteTheCurrentResult() {
        // Deliberately ignore AbortSignal to exercise the generation fence even
        // when cancellation cannot stop an already completed transport.
        page.evaluate("""
            (() => {
              const originalFetch = window.fetch;
              window.pendingSearches = [];
              window.fetch = (url, options) => url === '/api/search'
                ? new Promise(resolve => window.pendingSearches.push((data, status = 200) =>
                    resolve(new Response(JSON.stringify(data), {status, headers:{'Content-Type':'application/json'}}))))
                : originalFetch(url, options);
            })()
            """);
        page.locator("#searchForm input[name='expression']").fill("old + 0");
        page.locator("#searchForm button[type='submit']").click();
        page.locator("#searchForm input[name='expression']").fill("new + 0");
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction("() => window.pendingSearches.length === 2");
        page.evaluate("window.pendingSearches[1]({expression:'new + 0',bestExpression:'new',successes:1})");
        page.waitForFunction("() => document.getElementById('searchResultSummary').textContent.includes('new + 0 → new')");
        page.evaluate("window.pendingSearches[0]({expression:'old + 0',bestExpression:'old',successes:1})");
        page.evaluate("() => new Promise(requestAnimationFrame)");
        assertTrue(page.locator("#searchResultSummary").innerText().contains("new + 0 → new"));
        assertFalse(page.locator("#searchOutput").textContent().contains("old"));

        page.locator("#runtimeImport").setInputFiles(new com.microsoft.playwright.options.FilePayload(
            "pending.json", "application/json", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        page.waitForFunction("() => window.pendingSearches.length === 3");
        page.locator("#searchForm button[type='submit']").click();
        page.waitForFunction("() => window.pendingSearches.length === 4");
        page.evaluate("window.pendingSearches[3]({expression:'new + 0',bestExpression:'new',successes:1})");
        page.waitForFunction("() => document.getElementById('searchResultSummary').textContent.includes('new + 0 → new')");
        page.evaluate("window.pendingSearches[2]({error:'late replay failure'}, 500)");
        page.evaluate("() => new Promise(requestAnimationFrame)");
        assertTrue(page.locator("#searchResultSummary").innerText().contains("new + 0 → new"));
        assertTrue(page.locator("#runtimeExport").isDisabled());
        assertFalse(page.locator("#searchStatus").innerText().contains("fehlgeschlagen"));
    }
}
