package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code /api/didactic/*} endpoints introduced in PR 17.
 */
class DidacticApiTest {

    private WebWorkbenchServer server;
    private InMemoryExpressionGraphStore graphStore;

    @BeforeEach
    void start() throws IOException {
        graphStore = new InMemoryExpressionGraphStore();
        graphStore.saveDiscoveredTransformation(sampleDerivation());
        server = new WebWorkbenchServer(
            "127.0.0.1", 0,
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
    void stepCheckReturnsMisconceptionForFalseCancellation() throws IOException {
        String body = postJson("/api/didactic/step-check",
            "{\"currentExpression\":\"(a + b) / b\",\"studentStep\":\"a\","
                + "\"difficulty\":\"MITTELSTUFE\"}");
        assertTrue(body.contains("\"correct\":false"), body);
        assertTrue(body.contains("\"misconception\""), body);
        assertTrue(body.contains("false_cancellation_sum_in_numerator"), body);
    }

    @Test
    void stepCheckAcceptsEquivalentStep() throws IOException {
        // x + 0 is equivalent to x; the validator should accept.
        String body = postJson("/api/didactic/step-check",
            "{\"currentExpression\":\"x + 0\",\"studentStep\":\"x\","
                + "\"difficulty\":\"GRUNDSCHULE\"}");
        assertTrue(body.contains("\"correct\":true"), body);
    }

    @Test
    void stepCheckRejectsInvalidDifficulty() throws IOException {
        HttpURLConnection connection = open("POST", "/api/didactic/step-check");
        try (OutputStream os = connection.getOutputStream()) {
            os.write(("{\"currentExpression\":\"x\",\"studentStep\":\"x\","
                + "\"difficulty\":\"KINDERGARTEN\"}").getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(400, connection.getResponseCode());
    }

    @Test
    void hintReturnsGraduatedHintsForKnownPath() throws IOException {
        String body = postJson("/api/didactic/hint/sample-derivation-id",
            "{\"currentExpression\":\"a*(b + c)\",\"pedagogyProfile\":\"SCHOOL\"}");
        assertTrue(body.contains("\"strength\":\"SMALL\""), body);
        assertTrue(body.contains("\"strength\":\"STRONG\""), body);
        assertTrue(body.contains("\"strength\":\"FULL_STEP\""), body);
    }

    @Test
    void hintReturns404ForUnknownPath() throws IOException {
        HttpURLConnection connection = open("POST", "/api/didactic/hint/no-such-path");
        try (OutputStream os = connection.getOutputStream()) {
            os.write("{\"currentExpression\":\"a\"}".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(404, connection.getResponseCode());
    }

    @Test
    void hintRejectsGetMethod() throws IOException {
        HttpURLConnection connection = open("GET", "/api/didactic/hint/sample-derivation-id");
        assertEquals(405, connection.getResponseCode());
    }

    @Test
    void misconceptionsListsCatalogue() throws IOException {
        HttpURLConnection connection = open("GET", "/api/didactic/misconceptions");
        assertEquals(200, connection.getResponseCode());
        String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("false_cancellation_sum_in_numerator"), body);
        assertTrue(body.contains("sign_distribution_partial"), body);
        assertTrue(body.contains("inequality_missing_flip"), body);
    }

    @Test
    void replayReturnsSymbolDiffPerStep() throws IOException {
        HttpURLConnection connection = open("GET", "/api/didactic/replay/sample-derivation-id");
        assertEquals(200, connection.getResponseCode());
        String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"diffTokens\""), body);
        assertTrue(body.contains("\"change\":\"UNCHANGED\"")
            || body.contains("\"change\":\"ADDED\"")
            || body.contains("\"change\":\"REMOVED\""), body);
    }

    @Test
    void replayReturns404ForUnknownPath() throws IOException {
        HttpURLConnection connection = open("GET", "/api/didactic/replay/no-such-id");
        assertEquals(404, connection.getResponseCode());
    }

    @Test
    void exportWorksheetSolutionAndTeacherReturnMarkdown() throws IOException {
        for (String kind : new String[]{"worksheet", "solution", "teacher"}) {
            HttpURLConnection connection = open(
                "GET", "/api/didactic/export/" + kind + "/sample-derivation-id.md");
            assertEquals(200, connection.getResponseCode(), () -> "kind=" + kind);
            String body = new String(connection.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            assertTrue(body.startsWith("# "), () -> kind + " body: " + body);
            assertTrue(connection.getContentType().startsWith("text/markdown"),
                () -> "expected text/markdown for " + kind + " but got "
                    + connection.getContentType());
        }
    }

    @Test
    void exportUnknownKindReturns404() throws IOException {
        HttpURLConnection connection = open(
            "GET", "/api/didactic/export/poster/sample-derivation-id.md");
        assertEquals(404, connection.getResponseCode());
    }

    @Test
    void analyticsRecordsStepCheckAndHintEvents() throws IOException {
        // baseline
        String empty = getString("/api/didactic/analytics");
        assertTrue(empty.contains("\"totalEvents\":0"), empty);

        // trigger one of each kind
        postJson("/api/didactic/step-check",
            "{\"currentExpression\":\"x + 0\",\"studentStep\":\"x\","
                + "\"difficulty\":\"GRUNDSCHULE\"}");
        postJson("/api/didactic/hint/sample-derivation-id",
            "{\"currentExpression\":\"a*(b + c)\",\"pedagogyProfile\":\"SCHOOL\"}");

        String after = getString("/api/didactic/analytics");
        assertTrue(after.contains("\"stepChecks\":1"), after);
        assertTrue(after.contains("\"hints\":1"), after);
        assertTrue(after.contains("\"GRUNDSCHULE\":1"), after);
        assertTrue(after.contains("\"SCHOOL\":1"), after);
    }

    private String getString(String path) throws IOException {
        HttpURLConnection connection = open("GET", path);
        assertEquals(200, connection.getResponseCode(),
            () -> "expected 200 from " + path);
        return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    // -------- helpers --------

    private String postJson(String path, String payload) throws IOException {
        HttpURLConnection connection = open("POST", path);
        try (OutputStream os = connection.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, connection.getResponseCode(),
            () -> "expected 200 from " + path);
        return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private HttpURLConnection open(String method, String path) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.boundPort() + path);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", "application/json");
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
        }
        assertNotNull(method);
        return connection;
    }

    private static DiscoveredTransformation sampleDerivation() {
        TransformationStep step = new TransformationStep(
            0,
            "a*(b + c)",
            "a*b + a*c",
            "ast_distribute_left_add",
            RewriteKind.EXPAND,
            10, 12, true,
            "Distributivgesetz angewandt");
        return new DiscoveredTransformation(
            "sample-derivation-id",
            "a*(b + c)",
            "a*b + a*c",
            List.of(step),
            new ExpressionScore(8, 5, 2, 2, 0),
            new ExpressionScore(10, 7, 3, 2, 0),
            -2,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Instant.EPOCH,
            "hash");
    }
}
