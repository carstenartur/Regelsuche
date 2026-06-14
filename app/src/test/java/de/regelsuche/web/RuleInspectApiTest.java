package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.json.JsonReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the {@code /api/inspect/tree} endpoint introduced in
 * issue #106 (Rule Authoring IDE – tree-local rule inspection).
 */
class RuleInspectApiTest {

    private WebWorkbenchServer server;

    @BeforeEach
    void start() throws IOException {
        server = new WebWorkbenchServer(
                "127.0.0.1", 0,
                new InMemoryExpressionGraphStore(),
                new InMemoryRuleInventoryRepository(),
                new DefaultTransformationExportService());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void returnsJsonWithPositionsForQuadratic() throws IOException {
        String body = get("/api/inspect/tree?expression=x%5E2+%2B+6*x+%2B+5");
        assertTrue(body.contains("\"positions\""), body);
        assertTrue(body.contains("\"pathKey\""), body);
        assertTrue(body.contains("\"subtree\""), body);
        assertTrue(body.contains("\"selected\""), body);
        assertTrue(body.contains("\"matches\""), body);
    }

    @Test
    void includesRootPositionForSimpleExpression() throws IOException {
        String body = get("/api/inspect/tree?expression=x%5E2+%2B+6*x+%2B+5");
        assertTrue(body.contains("\"root\""), "expected root pathKey in: " + body);
    }

    @Test
    void includesBindingsAndRewritePreview() throws IOException {
        String body = get("/api/inspect/tree?expression=x%5E2+%2B+6*x+%2B+5");
        assertTrue(body.contains("\"bindings\""), body);
        assertTrue(body.contains("\"applicable\""), body);
        assertTrue(body.contains("\"rewriteBefore\""), body);
        assertTrue(body.contains("\"rewriteAfter\""), body);
    }

    @Test
    void applyEndpointReturnsUpdatedExpressionAndInspection() throws IOException {
        String inspect = get("/api/inspect/tree?expression=sin(x%5E2+%2B+6*x+%2B+5)");
        Map<String, Object> inspectJson = new JsonReader(inspect).readObject();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> positions = (List<Map<String, Object>>) inspectJson.get("positions");
        Map<String, Object> selectedPosition = positions.stream()
                .filter(p -> "000".equals(String.valueOf(p.get("pathKey"))))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) selectedPosition.get("matches");
        int completeSquareIndex = -1;
        for (int i = 0; i < matches.size(); i++) {
            if ("COMPLETE_SQUARE".equals(String.valueOf(matches.get(i).get("kind")))) {
                completeSquareIndex = i;
                break;
            }
        }
        assertTrue(completeSquareIndex >= 0, "COMPLETE_SQUARE match expected");

        String body = postJson("/api/inspect/tree/apply", """
                {"expression":"sin(x^2 + 6*x + 5)","pathKey":"000","matchIndex":%d}
                """.formatted(completeSquareIndex));
        assertTrue(body.contains("\"expressionAfter\""), body);
        assertTrue(body.contains("sin((x + 3) ^ 2 - 4)"), body);
        assertTrue(body.contains("\"inspection\""), body);
    }

    @Test
    void includesFullExpressionAfterForNestedRewrite() throws IOException {
        String body = get("/api/inspect/tree?expression=sin(x%5E2+%2B+6*x+%2B+5)");
        assertTrue(body.contains("\"subtreeBefore\""), body);
        assertTrue(body.contains("\"subtreeAfter\""), body);
        assertTrue(body.contains("\"expressionAfter\""), body);
        assertTrue(body.contains("sin((x + 3) ^ 2 - 4)"), body);
    }

    @Test
    void returnsNonRootPositionForNestedExpression() throws IOException {
        String body = get("/api/inspect/tree?expression=sin(x%5E2+%2B+6*x+%2B+5)");
        // At least one position must not be root
        assertTrue(body.contains("\"000\"") || body.contains("\"000."), body);
    }

    @Test
    void returns400ForMissingExpression() throws IOException {
        HttpURLConnection conn = openGet("/api/inspect/tree");
        assertEquals(400, conn.getResponseCode());
    }

    @Test
    void returns404ForUnknownSubPath() throws IOException {
        HttpURLConnection conn = openGet("/api/inspect/unknown");
        assertEquals(404, conn.getResponseCode());
    }

    @Test
    void returns405ForPostMethod() throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.boundPort()
                + "/api/inspect/tree?expression=x");
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        try (var out = conn.getOutputStream()) {
            out.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(405, conn.getResponseCode());
    }

    @Test
    void applyReturns400ForMissingFields() throws IOException {
        HttpURLConnection conn = openPost("/api/inspect/tree/apply", "{}");
        assertEquals(400, conn.getResponseCode());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String get(String path) throws IOException {
        HttpURLConnection conn = openGet(path);
        assertEquals(200, conn.getResponseCode(), () -> "expected 200 from " + path);
        try (var in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private HttpURLConnection openGet(String path) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.boundPort() + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("GET");
        return conn;
    }

    private String postJson(String path, String json) throws IOException {
        HttpURLConnection conn = openPost(path, json);
        assertEquals(200, conn.getResponseCode(), () -> "expected 200 from " + path);
        try (var in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private HttpURLConnection openPost(String path, String json) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.boundPort() + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (var out = conn.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }
}
