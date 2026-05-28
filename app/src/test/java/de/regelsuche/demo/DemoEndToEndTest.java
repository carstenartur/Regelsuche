package de.regelsuche.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.web.WebWorkbenchServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke tests for the killer-app demo flow: each demo endpoint
 * must run a search, produce a graph, expose a path replay, list identities
 * and emit a multi-format report bundle without any further configuration.
 */
class DemoEndToEndTest {

    private WebWorkbenchServer server;
    private InMemoryExpressionGraphStore graphStore;

    @BeforeEach
    void start() throws IOException {
        graphStore = new InMemoryExpressionGraphStore();
        server = new WebWorkbenchServer(
            "127.0.0.1",
            0,
            graphStore,
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService()
        );
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void demoBinomialFindsExpectedExpansion() throws IOException {
        String body = runDemo("binomial");
        assertTrue(body.contains("\"id\":\"binomial\""), body);
        assertTrue(body.contains("\"expression\":\"(x+3)^2\""), body);
        // The selected path must reach the canonical binomial expansion
        // 9 + 6*x + x^2 (real mathematical hit, not just HTTP 200).
        assertTrue(body.contains("\"targetReached\":true"),
            "binomial demo must reach the canonical target: " + body);
        assertTrue(body.contains("\"canonicalTargetExpression\":\"x ^ 2 + 6 * x + 9\""),
            "binomial demo target must be the collected canonical polynomial: " + body);
        assertTrue(body.contains("\"improvedExpression\":\"x ^ 2 + 6 * x + 9\""),
            "selected path must end at x ^ 2 + 6 * x + 9: " + body);
        assertTrue(body.contains("\"ruleId\":\"polynomial_collect_like_terms\""),
            "selected path must expose the collect-like-terms step: " + body);
        assertTrue(graphStore.snapshot().nodes().contains("x ^ 2 + 6 * x + 9"),
            "binomial graph must contain final collected node");
        assertTrue(graphStore.snapshot().edges().stream().anyMatch(edge ->
                edge.toExpression().equals("x ^ 2 + 6 * x + 9")
                    && edge.transformationRule().equals("polynomial_collect_like_terms")),
            "binomial graph must contain collect-like-terms edge to final node");
        assertTrue(graphStore.discoveredTransformations().size() > 0,
            "expected demo to record discovered transformations");
        String firstPathId = graphStore.discoveredTransformations().get(0).id();
        String replay = getText("/api/paths/" + firstPathId + "/replay");
        assertTrue(replay.contains("\"steps\""), replay);
    }

    @Test
    void demoRationalCancelsCommonFactorWithAssumption() throws IOException {
        String body = runDemo("rational");
        assertTrue(body.contains("\"id\":\"rational\""), body);
        assertTrue(body.contains("(x*y)/(x*z)"), body);
        // Must reach y / z (real cancellation, not just a populated graph).
        assertTrue(body.contains("\"targetReached\":true"),
            "rational demo must reach the canonical target y / z: " + body);
        assertTrue(body.contains("\"y / z\""),
            "selected path must end at y / z: " + body);
        // Assumption x != 0 must be surfaced.
        assertTrue(body.contains("\"x != 0\""),
            "rational demo must surface assumption x != 0: " + body);
    }

    @Test
    void demoTrigonometryFindsPythagoreanIdentity() throws IOException {
        String body = runDemo("trigonometry");
        assertTrue(body.contains("\"id\":\"trigonometry\""), body);
        // sin(x)^2 + cos(x)^2 must reduce to the literal 1.
        assertTrue(body.contains("\"targetReached\":true"),
            "trigonometry demo must reach the Pythagorean identity 1: " + body);
        assertTrue(body.contains("\"improvedExpression\":\"1\""),
            "selected path must end at 1: " + body);
    }

    @Test
    void demoEquationSolvesOrClearlyTransformsEquation() throws IOException {
        // The demo formerly known as "equation" has been honestly renamed to
        // "polynomial-expansion" because the current atomic rule set does
        // not solve linear equations. The old id stays as an alias.
        String body = runDemo("polynomial-expansion");
        assertTrue(body.contains("\"id\":\"polynomial-expansion\""), body);
        assertTrue(body.contains("(x+1)*(x+2)"), body);
        assertTrue(body.contains("\"targetReached\":true"),
            "polynomial-expansion demo must reach 2 + 3*x + x^2: " + body);
        assertTrue(body.contains("x ^ 2 + 3 * x + 2"),
            "selected path must end at x ^ 2 + 3 * x + 2: " + body);
        assertTrue(graphStore.snapshot().nodes().contains("x ^ 2 + 3 * x + 2"),
            "polynomial graph must contain final collected node");
        assertTrue(graphStore.snapshot().edges().stream().anyMatch(edge ->
                edge.toExpression().equals("x ^ 2 + 3 * x + 2")
                    && edge.transformationRule().equals("polynomial_collect_like_terms")),
            "polynomial graph must contain a collect like terms edge");
        assertTrue(body.contains("\\\\text{collect like terms}") || body.contains("polynomial_collect_like_terms"),
            "polynomial graph response must expose collect like terms label: " + body);

        // Backwards-compatible alias: /api/demo/equation must still resolve.
        String aliasBody = runDemo("equation");
        assertTrue(aliasBody.contains("\"id\":\"polynomial-expansion\""),
            "/api/demo/equation must alias to polynomial-expansion: " + aliasBody);
    }

    @Test
    void demoBundleContainsAllExpectedFiles() throws IOException {
        // Run binomial first so the export bundle has content.
        runDemo("binomial");

        byte[] zipBytes = getBytes("/api/exports/bundle.zip");
        assertTrue(zipBytes.length > 100, "bundle zip should not be empty");
        Set<String> entries = listZipEntries(zipBytes);
        // All eight required entries must be present.
        assertTrue(entries.contains("search-analysis-report.md"), entries.toString());
        assertTrue(entries.contains("search-analysis-report.tex"), entries.toString());
        assertTrue(entries.contains("search-analysis-report.json"), entries.toString());
        assertTrue(entries.contains("search-graph.json"), entries.toString());
        assertTrue(entries.contains("search-graph.mmd"), entries.toString());
        assertTrue(entries.contains("search-graph.graphml"), entries.toString());
        assertTrue(entries.contains("best-path.md"), entries.toString());
        assertTrue(entries.contains("rule-inventory.json"), entries.toString());
        assertTrue(entries.contains("pruning-decisions.json"), entries.toString());
    }

    @Test
    void demoReportContainsGraphReplayAndRuleInventory() throws IOException {
        runDemo("binomial");

        // Markdown analysis report must include the canonical sections.
        String md = getText("/api/exports/search-analysis-report.md");
        assertTrue(md.contains("Suchanalyse"), md);
        assertTrue(md.contains("Graphmetriken"), md);
        assertTrue(md.contains("Bester Pfad") || md.contains("bester Pfad"), md);

        // Bundle must include a parseable rule-inventory.json with the
        // expected top-level shape.
        byte[] zipBytes = getBytes("/api/exports/bundle.zip");
        String ruleInventory = readZipEntry(zipBytes, "rule-inventory.json");
        assertNotNull(ruleInventory);
        assertTrue(ruleInventory.contains("\"reusableRules\""),
            "rule-inventory.json must contain reusableRules array: " + ruleInventory);

        // Search graph endpoint must return non-empty JSON after a demo ran.
        String searchGraph = getText("/api/search-graph");
        assertTrue(searchGraph.contains("\"nodes\""), searchGraph);
        // Inventory endpoint must still be reachable.
        String inventory = getText("/api/inventory");
        assertTrue(inventory.startsWith("{\"rules\""), inventory);
    }

    private String runDemo(String id) throws IOException {
        HttpURLConnection conn = open("/api/demo/" + id);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        // No body needed.
        conn.getOutputStream().close();
        assertEquals(200, conn.getResponseCode(),
            "demo " + id + " must return 200 (got " + conn.getResponseCode() + ")");
        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String getText(String path) throws IOException {
        HttpURLConnection conn = open(path);
        assertEquals(200, conn.getResponseCode(), "GET " + path + " expected 200");
        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private byte[] getBytes(String path) throws IOException {
        HttpURLConnection conn = open(path);
        assertEquals(200, conn.getResponseCode(), "GET " + path + " expected 200");
        return conn.getInputStream().readAllBytes();
    }

    private HttpURLConnection open(String path) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.boundPort() + path);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(15000);
        assertNotNull(connection);
        return connection;
    }

    private Set<String> listZipEntries(byte[] bytes) throws IOException {
        Set<String> entries = new HashSet<>();
        try (InputStream in = new java.io.ByteArrayInputStream(bytes);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                zip.transferTo(sink);
                zip.closeEntry();
            }
        }
        return entries;
    }

    private String readZipEntry(byte[] bytes, String name) throws IOException {
        try (InputStream in = new java.io.ByteArrayInputStream(bytes);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    ByteArrayOutputStream sink = new ByteArrayOutputStream();
                    zip.transferTo(sink);
                    zip.closeEntry();
                    return sink.toString(StandardCharsets.UTF_8);
                }
                zip.closeEntry();
            }
        }
        return null;
    }
}
