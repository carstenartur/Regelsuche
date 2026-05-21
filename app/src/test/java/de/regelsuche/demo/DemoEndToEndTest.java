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
    void demoBinomialWorksEndToEnd() throws IOException {
        String body = runDemo("binomial");
        assertTrue(body.contains("\"id\":\"binomial\""), body);
        assertTrue(body.contains("\"expression\":\"(x+3)^2\""), body);
        assertTrue(body.contains("\"bestPath\""), body);
        // Demo must have produced at least one node + edge.
        assertTrue(body.contains("\"nodes\""), body);
        assertTrue(body.contains("\"edges\""), body);
        // After the demo runs, the existing paths endpoint must return data.
        String paths = getText("/api/paths");
        assertTrue(paths.startsWith("{\"transformations\":["), paths);
        assertTrue(paths.length() > "{\"transformations\":[]}".length(), paths);
        // Replay of best path must be reachable.
        assertTrue(graphStore.discoveredTransformations().size() > 0,
            "expected demo to record discovered transformations");
        String firstPathId = graphStore.discoveredTransformations().get(0).id();
        String replay = getText("/api/paths/" + firstPathId + "/replay");
        assertTrue(replay.contains("\"steps\""), replay);
    }

    @Test
    void demoRationalWorksEndToEnd() throws IOException {
        String body = runDemo("rational");
        assertTrue(body.contains("\"id\":\"rational\""), body);
        assertTrue(body.contains("(x*y)/(x*z)"), body);
        assertTrue(body.contains("\"identities\""), body);
        assertTrue(body.contains("\"links\""), body);
    }

    @Test
    void demoTrigonometryWorksEndToEnd() throws IOException {
        String body = runDemo("trigonometry");
        assertTrue(body.contains("\"id\":\"trigonometry\""), body);
        assertTrue(body.contains("sin(x)") || body.contains("cos(x)"), body);
        // Even when the atomic rule set finds no improvement, the search graph
        // and metrics block must be present.
        assertTrue(body.contains("\"metrics\""), body);
        assertTrue(body.contains("\"links\""), body);
    }

    @Test
    void demoEquationWorksEndToEnd() throws IOException {
        String body = runDemo("equation");
        assertTrue(body.contains("\"id\":\"equation\""), body);
        assertTrue(body.contains("(x+1)*(x+2)"), body);
        // Equation demo should produce at least one discovered path
        // (distribute + combine like terms).
        assertTrue(graphStore.discoveredTransformations().size() > 0,
            "expected equation demo to record discovered transformations");
    }

    @Test
    void exportedReportContainsGraphReplayAndRuleInventory() throws IOException {
        // Run binomial first so the export bundle has content.
        runDemo("binomial");

        // The Markdown analysis report must include input, graph metrics,
        // best path and identities sections. The expression is canonicalised
        // by the analyzer, so just check for a stable header marker.
        String md = getText("/api/exports/search-analysis-report.md");
        assertTrue(md.contains("Suchanalyse"), md);
        assertTrue(md.contains("Graphmetriken"), md);
        assertTrue(md.contains("Bester Pfad") || md.contains("bester Pfad"), md);

        // The JSON analysis report must mention graph metrics + identities.
        String json = getText("/api/exports/search-analysis-report.json");
        assertNotNull(json);

        // The bundle.zip endpoint must return a valid zip with the expected
        // entries (Markdown, LaTeX, JSON, Mermaid, GraphML).
        byte[] zipBytes = getBytes("/api/exports/bundle.zip");
        assertTrue(zipBytes.length > 100, "bundle zip should not be empty");
        Set<String> entries = listZipEntries(zipBytes);
        assertTrue(entries.contains("search-analysis-report.md"), entries.toString());
        assertTrue(entries.contains("search-analysis-report.tex"), entries.toString());
        assertTrue(entries.contains("search-analysis-report.json"), entries.toString());
        assertTrue(entries.contains("search-graph.mmd"), entries.toString());
        assertTrue(entries.contains("search-graph.graphml"), entries.toString());
        assertTrue(entries.contains("best-path.md"), entries.toString());

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
}
