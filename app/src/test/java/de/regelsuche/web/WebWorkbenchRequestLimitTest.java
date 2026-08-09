package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebWorkbenchRequestLimitTest {
    private static final int REQUEST_LIMIT = 1024;

    private WebWorkbenchServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        WebSecurityConfig security = WebSecurityConfig.builder()
            .maxRequestBytes(REQUEST_LIMIT)
            .build();
        server = new WebWorkbenchServer(
            "127.0.0.1",
            0,
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            security);
        server.start();
        port = server.boundPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void acceptsExactBoundaryRejectsOversizedFixedAndChunkedBodiesAndRemainsResponsive()
            throws IOException {
        HttpURLConnection exact = postFixed(
            "/api/proof-bridge",
            proofBridgeBody(REQUEST_LIMIT));
        assertEquals(200, exact.getResponseCode());
        assertTrue(readBody(exact).contains("\"proofStatus\""));

        assertPayloadTooLarge(postFixed(
            "/api/proof-bridge",
            proofBridgeBody(REQUEST_LIMIT + 1)));
        assertPayloadTooLarge(postChunked(
            "/api/proof-bridge",
            proofBridgeBody(REQUEST_LIMIT + 1)));

        HttpURLConnection followUp = open("/api/proof-status");
        assertEquals(200, followUp.getResponseCode());
        assertTrue(readBody(followUp).contains("\"statuses\""));
    }

    @Test
    void allImmediateWorkbenchJsonPostSurfacesShareTheTyped413Boundary()
            throws IOException {
        byte[] oversized = proofBridgeBody(REQUEST_LIMIT + 1);
        for (String path : List.of(
                "/api/search",
                "/api/discover",
                "/api/inventory",
                "/api/inspect/tree/apply",
                "/api/didactic/step-check",
                "/api/proof-bridge")) {
            assertPayloadTooLarge(postFixed(path, oversized));
        }
    }

    @Test
    void workbenchSourceHasNoIndependentRequestBodyReader() throws IOException {
        Path root = repositoryRoot();
        String source = Files.readString(
            root.resolve("app/src/main/java/de/regelsuche/web/WebWorkbenchServer.java"),
            StandardCharsets.UTF_8);

        assertFalse(source.contains("getRequestBody()"),
            "WebWorkbenchServer must not bypass the shared bounded reader");
        assertTrue(source.contains("BoundedRequestBody.read("),
            "WebWorkbenchServer must use the shared bounded reader");
        assertTrue(source.contains("PayloadTooLargeException"),
            "the API boundary must catch the typed oversized-body signal");
    }

    private void assertPayloadTooLarge(HttpURLConnection connection)
            throws IOException {
        assertEquals(413, connection.getResponseCode());
        assertTrue(connection.getHeaderField("Content-Type")
            .startsWith("application/json"));
        assertEquals("no-store", connection.getHeaderField("Cache-Control"));
        String error = readBody(connection);
        assertTrue(error.contains("\"error\":true"), error);
        assertTrue(error.contains("\"code\":\"PAYLOAD_TOO_LARGE\""), error);
        assertTrue(error.contains(
            "\"message\":\"request body exceeds configured limit\""), error);
        assertTrue(error.contains("\"limitBytes\":1024"), error);
    }

    private HttpURLConnection postFixed(String path, byte[] body)
            throws IOException {
        HttpURLConnection connection = preparePost(path);
        connection.setFixedLengthStreamingMode(body.length);
        writeBody(connection, body);
        return connection;
    }

    private HttpURLConnection postChunked(String path, byte[] body)
            throws IOException {
        HttpURLConnection connection = preparePost(path);
        connection.setChunkedStreamingMode(128);
        writeBody(connection, body);
        return connection;
    }

    private HttpURLConnection preparePost(String path) throws IOException {
        HttpURLConnection connection = open(path);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        return connection;
    }

    private void writeBody(HttpURLConnection connection, byte[] body)
            throws IOException {
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
    }

    private HttpURLConnection open(String path) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        HttpURLConnection connection =
            (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(5000);
        return connection;
    }

    private byte[] proofBridgeBody(int length) {
        String prefix =
            "{\"leftPattern\":\"x+0\",\"rightPattern\":\"x\",\"padding\":\"";
        String suffix = "\"}";
        int paddingLength = length - prefix.length() - suffix.length();
        if (paddingLength < 0) {
            throw new IllegalArgumentException(
                "length is too small for the JSON envelope");
        }
        return (prefix + "x".repeat(paddingLength) + suffix)
            .getBytes(StandardCharsets.UTF_8);
    }

    private String readBody(HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getResponseCode() >= 400
            ? connection.getErrorStream()
            : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("repository root not found");
        }
        return root;
    }
}
