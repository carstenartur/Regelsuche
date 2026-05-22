package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the {@code goal} field on {@code POST /api/search} is
 * accepted, validated and echoed back. Wires only the bare workbench
 * server; the underlying search uses real engines and an in-memory graph
 * store, which is cheap for tiny terms.
 */
class SearchGoalApiTest {

    private WebWorkbenchServer server;

    @BeforeEach
    void start() throws IOException {
        server = new WebWorkbenchServer(
            "127.0.0.1", 0,
            new InMemoryExpressionGraphStore(),
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
    void searchEchoesGoalWhenProvided() throws IOException {
        String body = postSearch("{\"expression\":\"x+0\",\"profile\":\"FAST_SIMPLIFY\","
            + "\"goal\":\"TEACHING_FRIENDLY\"}");
        assertTrue(body.contains("\"goal\":\"TEACHING_FRIENDLY\""), body);
    }

    @Test
    void searchUsesProfileDefaultGoalWhenOmitted() throws IOException {
        // FAST_SIMPLIFY's default goal is SIMPLIFY.
        String body = postSearch("{\"expression\":\"x+0\",\"profile\":\"FAST_SIMPLIFY\"}");
        assertTrue(body.contains("\"goal\":\"SIMPLIFY\""), body);
    }

    @Test
    void searchRejectsUnknownGoal() throws IOException {
        HttpURLConnection connection = open();
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = connection.getOutputStream()) {
            os.write("{\"expression\":\"x+0\",\"goal\":\"NO_SUCH_GOAL\"}"
                .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(400, connection.getResponseCode());
    }

    private String postSearch(String payload) throws IOException {
        HttpURLConnection connection = open();
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = connection.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, connection.getResponseCode());
        return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private HttpURLConnection open() throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.boundPort() + "/api/search");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod("POST");
        return connection;
    }
}
