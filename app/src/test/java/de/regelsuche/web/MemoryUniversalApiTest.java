package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Smoke-test for {@code GET /api/memory/universal}: the endpoint must
 * always respond with the documented JSON shape (patterns + ruleCoverage)
 * even when the search memory is empty.
 */
class MemoryUniversalApiTest {

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
    void emptyMemoryUniversalReturnsArrays() throws IOException {
        HttpURLConnection connection = open("/api/memory/universal");
        assertEquals(200, connection.getResponseCode());
        String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"patterns\""), body);
        assertTrue(body.contains("\"ruleCoverage\""), body);
    }

    @Test
    void memoryIndexAdvertisesUniversalLink() throws IOException {
        HttpURLConnection connection = open("/api/memory");
        assertEquals(200, connection.getResponseCode());
        String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("/api/memory/universal"), body);
    }

    private HttpURLConnection open(String path) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.boundPort() + path);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(5000);
        return connection;
    }
}
