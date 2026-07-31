package de.regelsuche.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.plugin.PluginRuntimeConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuleRadarHttpHandlerRequestLimitTest {
    private static final int REQUEST_LIMIT = 1024;

    private HttpServer server;
    private RuleRadarHttpHandler handler;

    @BeforeEach
    void startServer() throws IOException {
        handler = new RuleRadarHttpHandler(
            new InMemoryRuleInventoryRepository(),
            new InMemoryExpressionGraphStore(),
            PluginRuntimeConfig.defaults(),
            REQUEST_LIMIT
        );
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/rule-radar", handler);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (handler != null) {
            handler.close();
        }
    }

    @Test
    void acceptsExactBoundaryRejectsOversizedFixedAndChunkedBodiesAndRemainsResponsive()
        throws IOException {
        HttpURLConnection exact = postFixed("/api/rule-radar/inspect", jsonBody(REQUEST_LIMIT));
        assertEquals(200, exact.getResponseCode());
        assertTrue(readBody(exact).contains("\"schema\":\"regelsuche.ast-rule-radar/v1\""));

        assertPayloadTooLarge(postFixed(
            "/api/rule-radar/inspect",
            jsonBody(REQUEST_LIMIT + 1)
        ));
        assertPayloadTooLarge(postChunked(
            "/api/rule-radar/inspect",
            jsonBody(REQUEST_LIMIT + 1)
        ));

        HttpURLConnection followUp = open("/api/rule-radar");
        assertEquals(200, followUp.getResponseCode());
        assertTrue(readBody(followUp).contains("\"schema\":\"regelsuche.ast-rule-radar-http/v1\""));
    }

    private void assertPayloadTooLarge(HttpURLConnection connection) throws IOException {
        assertEquals(413, connection.getResponseCode());
        assertTrue(connection.getHeaderField("Content-Type").startsWith("application/json"));
        String error = readBody(connection);
        assertTrue(error.contains("\"code\":\"PAYLOAD_TOO_LARGE\""), error);
        assertTrue(error.contains("\"limitBytes\":1024"), error);
    }

    private HttpURLConnection postFixed(String path, byte[] body) throws IOException {
        HttpURLConnection connection = preparePost(path);
        connection.setFixedLengthStreamingMode(body.length);
        writeBody(connection, body);
        return connection;
    }

    private HttpURLConnection postChunked(String path, byte[] body) throws IOException {
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

    private void writeBody(HttpURLConnection connection, byte[] body) throws IOException {
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
    }

    private HttpURLConnection open(String path) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(5000);
        return connection;
    }

    private byte[] jsonBody(int length) {
        String prefix = "{\"expression\":\"x\",\"padding\":\"";
        String suffix = "\"}";
        int paddingLength = length - prefix.length() - suffix.length();
        if (paddingLength < 0) {
            throw new IllegalArgumentException("length is too small for the JSON envelope");
        }
        return (prefix + "x".repeat(paddingLength) + suffix).getBytes(StandardCharsets.UTF_8);
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
}
