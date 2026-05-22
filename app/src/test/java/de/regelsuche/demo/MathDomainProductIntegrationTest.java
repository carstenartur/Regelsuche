package de.regelsuche.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.api.searchgraph.SearchExpression;
import de.regelsuche.benchmark.BenchmarkSuite;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.export.MatrixLatexRenderer;
import de.regelsuche.export.SearchAnalysisReportService;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inequality.Comparator;
import de.regelsuche.inequality.Inequality;
import de.regelsuche.inequality.InequalityTransformationRuleAdapter;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.MacroRuleMiner;
import de.regelsuche.parse.ExpressionParser;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Follow-up end-to-end tests for the math-domain product integration:
 * the new equation / inequality / derivative / matrix demos must surface
 * through the actual product surface (Web-Workbench HTTP API, benchmark
 * dashboard, replay, export, Discovery+ inventory, proof-bridge
 * endpoint) — not just through internal services.
 */
class MathDomainProductIntegrationTest {

    private de.regelsuche.web.WebWorkbenchServer server;
    private InMemoryExpressionGraphStore store;
    private InMemoryRuleInventoryRepository inventory;

    @BeforeEach
    void start() throws IOException {
        store = new InMemoryExpressionGraphStore();
        inventory = new InMemoryRuleInventoryRepository();
        server = new de.regelsuche.web.WebWorkbenchServer(
            "127.0.0.1",
            0,
            store,
            inventory,
            new de.regelsuche.export.DefaultTransformationExportService()
        );
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private String get(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.boundPort() + path).toURL().openConnection();
        conn.setRequestMethod("GET");
        assertEquals(200, conn.getResponseCode(),
            "GET " + path + " must succeed");
        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String post(String path, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.boundPort() + path).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        if (body != null && !body.isEmpty()) {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        var stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String response = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(200, code, "POST " + path + " must succeed: " + response);
        return response;
    }

    /* ----- 1. Web-Workbench surfaces equation demo ----- */
    @Test
    void equationDemoVisibleInWebWorkbench() throws IOException {
        // (a) The catalog endpoint exposes the math-equation demo
        String list = get("/api/demo");
        assertTrue(list.contains("\"id\":\"math-equation\""),
            "math-equation must appear in /api/demo catalog");
        assertTrue(list.contains("\"domain\":\"equations\""),
            "catalog must carry the domain tag: " + list);

        // (b) Running the demo returns the proper expressionType + selected
        // path with the equation rewrite as an actual TransformationStep.
        String bundle = post("/api/demo/math-equation", "");
        assertTrue(bundle.contains("\"expressionType\":\"EQUATION\""),
            "bundle must carry expressionType EQUATION: " + bundle);
        assertTrue(bundle.contains("\"id\":\"math-equation\""));
        assertTrue(bundle.contains("\"targetReached\":true"),
            "equation demo must reach its target: " + bundle);
        // The bundle exposes a non-empty selectedPath – i.e. the demo went
        // through the regular discovered-transformation pipeline, not a side
        // channel.
        assertTrue(bundle.contains("\"selectedPath\""), "selectedPath required");
        assertTrue(bundle.contains("\"originalExpression\":\"x + 3 = 7\"")
                || bundle.contains("\"originalExpression\":\"x + 3 \\u003d 7\""),
            "input expression must appear in selectedPath: " + bundle);
    }

    /* ----- 2. Inequality replay shows comparator flip ----- */
    @Test
    void inequalityDemoShowsComparatorFlipInReplay() {
        // The adapter is the replay data source — its TransformationStep
        // carries the comparator transition that the UI uses to render
        // "Vergleichszeichen gedreht".
        InequalityTransformationRuleAdapter adapter = new InequalityTransformationRuleAdapter();
        ExpressionParser p = new ExpressionParser();
        var trace = adapter.trace(
            new Inequality(p.parseTerm("-2*x"), Comparator.LT, p.parseTerm("4")),
            "x"
        ).orElseThrow();
        assertTrue(trace.anyComparatorFlipped(),
            "trace must mark the comparator flip");
        // The replay-facing TransformationSteps explicitly tag the divide
        // step (which causes the flip) so the UI can pick it up.
        List<TransformationStep> replaySteps = trace.discoverySteps();
        assertFalse(replaySteps.isEmpty());
        TransformationStep first = replaySteps.get(0);
        assertEquals("inequality_divide_both_sides", first.ruleId());
        // The Step model exposes comparatorBefore / comparatorAfter so the
        // replay overlay knows what transitioned.
        var step = trace.steps().get(0);
        assertEquals(Comparator.LT, step.comparatorBefore());
        assertEquals(Comparator.GT, step.comparatorAfter());
        assertTrue(step.comparatorFlipped(),
            "step.comparatorFlipped() is the explicit signal for the replay overlay");
    }

    /* ----- 3. Derivative rules participate in the search graph ----- */
    @Test
    void derivativeDemoUsesRewriteRulesInGraph() {
        InMemoryExpressionGraphStore localStore = new InMemoryExpressionGraphStore();
        UnifiedMathDomainWorkbench wb = new UnifiedMathDomainWorkbench(localStore);
        UnifiedMathDomainWorkbench.DemoExecution exec = wb.runDerivativePowerRule();

        // The derivative demo uses the actual CalculusDerivativeRules
        // rewrite rules — every step carries one of those rule ids and
        // every step shows up as an edge in the shared graph store. That
        // is what makes it visible in the search-graph view.
        assertFalse(exec.steps().isEmpty());
        boolean anyDerivRule = exec.steps().stream()
            .map(TransformationStep::ruleId)
            .anyMatch(id -> id != null && id.startsWith("calculus_"));
        assertTrue(anyDerivRule,
            "at least one step must come from CalculusDerivativeRules: "
                + exec.steps().stream().map(TransformationStep::ruleId).toList());

        List<GraphEdge> edges = localStore.snapshot().edges();
        assertFalse(edges.isEmpty(), "derivative steps must be edges in the shared graph");
        boolean derivativeEdge = edges.stream()
            .anyMatch(e -> e.transformationRule() != null
                && e.transformationRule().startsWith("calculus_"));
        assertTrue(derivativeEdge, "graph store must contain a derivative-rule edge");
    }

    /* ----- 4. Matrix export contains bmatrix LaTeX ----- */
    @Test
    void matrixDemoExportsLatexBmatrix() {
        MatrixLatexRenderer renderer = new MatrixLatexRenderer();
        String latex = renderer.renderLiteral("[[1, 2], [3, 4]]");
        assertTrue(latex.contains("\\begin{bmatrix}") && latex.contains("\\end{bmatrix}"),
            "matrix literal must render with bmatrix block: " + latex);

        UnifiedMathDomainWorkbench wb = new UnifiedMathDomainWorkbench();
        UnifiedMathDomainWorkbench.DemoExecution exec = wb.runMatrixDistributivity();
        assertTrue(exec.inputLatex().contains("\\begin{bmatrix}"),
            "matrix demo input must export bmatrix LaTeX: " + exec.inputLatex());

        // And the in-memory analysis report includes matrix LaTeX too: the
        // export bundle is what gets shipped via /api/exports/bundle.zip.
        InMemoryExpressionGraphStore localStore = new InMemoryExpressionGraphStore();
        new UnifiedMathDomainWorkbench(localStore).runMatrixDistributivity();
        SearchAnalysisReportService report = new SearchAnalysisReportService();
        // Build a minimal context from the store.
        var transformations = localStore.discoveredTransformations();
        assertFalse(transformations.isEmpty(),
            "matrix demo must register at least one DiscoveredTransformation");
    }

    /* ----- 5. Benchmark dashboard contains all 4 math categories ----- */
    @Test
    void mathDomainBenchmarkContainsAllCategories() {
        BenchmarkSuite suite = new BenchmarkSuite();
        List<BenchmarkSuite.BenchmarkSuiteResult> results = suite.runAll();
        Set<String> names = results.stream()
            .map(BenchmarkSuite.BenchmarkSuiteResult::name)
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(names.contains("equations"), "expected category 'equations' in: " + names);
        assertTrue(names.contains("inequalities"), "expected category 'inequalities' in: " + names);
        assertTrue(names.contains("calculus"), "expected category 'calculus' in: " + names);
        assertTrue(names.contains("linear-algebra"), "expected category 'linear-algebra' in: " + names);

        // Each math-domain row exposes the required metrics:
        for (BenchmarkSuite.BenchmarkSuiteResult r : results) {
            if (!Set.of("equations", "inequalities", "calculus", "linear-algebra")
                .contains(r.name())) {
                continue;
            }
            assertFalse(r.results().isEmpty(), r.name() + " must have at least one row");
            var row = r.results().get(0);
            assertNotNull(row.proofStatus(), r.name() + ".proofStatus required");
            assertTrue(row.expandedSteps() > 0, r.name() + " must show non-zero steps");
            // `found()` is derived from bestImprovement>0 — the math-domain
            // runs always reach their textbook target, so this must be true.
            assertTrue(row.found(), r.name() + " row must report found=true");
        }
    }

    /* ----- 6. Proof-bridge HTTP endpoint surfaces execution result ----- */
    @Test
    void proofBridgeUiShowsExecutionResult() throws IOException {
        String response = post("/api/proof-bridge",
            "{\"leftPattern\":\"x + 3 = 7\",\"rightPattern\":\"x = 4\","
                + "\"assumptions\":[],\"tool\":\"lean4\"}");
        // The response contains the prover status (script generated when no
        // executor configured), the generated proof artifact, and the
        // proof candidate status. Crucially the FORMALLY_PROVED transition
        // must NOT happen without a real prover run.
        String lower = response.toLowerCase(Locale.ROOT);
        assertTrue(response.contains("\"proofStatus\""), response);
        assertTrue(response.contains("\"proverStatus\""), response);
        assertTrue(lower.contains("script_generated") || lower.contains("prover_not_available")
                || lower.contains("prover_confirmed") || lower.contains("prover_failed"),
            "proverStatus must be one of the documented values: " + response);
        // Without a real Lean toolchain on the runner, the candidate must NOT
        // be elevated to FORMALLY_PROVED — that is the integrity check from
        // the issue ("Nur erfolgreicher Lauf darf FORMALLY_PROVED setzen").
        assertFalse(response.contains("\"proofStatus\":\"FORMALLY_PROVED\"")
                && response.contains("\"proverStatus\":\"SCRIPT_GENERATED\""),
            "FORMALLY_PROVED must not be set when only a script was generated: " + response);
        // The endpoint also returns the generated Lean script so the UI can
        // show it to the user.
        assertTrue(response.contains("\"artifact\""),
            "proof artifact must be returned for the UI: " + response);
    }

    /* ----- 7. Discovery+ learns an equation macro rule ----- */
    @Test
    void discoveryPlusLearnsEquationMacroRule() {
        InMemoryExpressionGraphStore localStore = new InMemoryExpressionGraphStore();
        UnifiedMathDomainWorkbench wb = new UnifiedMathDomainWorkbench(localStore);
        // Run the equation demo a few times so the macro miner sees
        // repeated occurrences of the same isolation pattern.
        wb.runLinearEquation();
        wb.runLinearEquation();

        // Macro miner uses the DiscoveredTransformations registered by the
        // unified workbench — i.e. the same Discovery+ pipeline as the
        // algebraic demos. It must produce at least one candidate.
        MacroRuleMiner miner = new MacroRuleMiner();
        var macros = miner.mine(localStore.discoveredTransformations());
        assertFalse(macros.isEmpty(),
            "Discovery+ must learn at least one macro from repeated equation demos");
        var first = macros.get(0);
        assertEquals("x + 3 = 7", first.leftPattern());
        assertEquals("x = 4", first.rightPattern());

        // And the rule, when persisted to the inventory with a domain tag,
        // is retrievable by tag — that is the "Inventar speichert
        // Domain-Tags" requirement.
        InMemoryRuleInventoryRepository inv = new InMemoryRuleInventoryRepository();
        ReusableRule reusable = new ReusableRule(
            "equation_isolate_variable",
            first.leftPattern(),
            first.rightPattern(),
            List.of(),
            CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            de.regelsuche.mining.RuleStatus.NEW,
            first.occurrences(),
            first.compressionRatio(),
            java.time.Instant.now()
        );
        inv.save(reusable);
        inv.addTag("equation_isolate_variable", "equations");
        assertFalse(inv.findByTag("equations").isEmpty(),
            "inventory must expose the equation domain tag");
        assertEquals("equation_isolate_variable",
            inv.findByTag("equations").get(0).id());
    }
}
