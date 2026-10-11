package de.regelsuche.quality.coverage;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeCodeCoverageClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMMIT = "1234567890123456789012345678901234567890";
    private static final String TOKEN = "private-test-credential";
    private static final byte[] XML = "<coverage><sources><source>ä</source></sources></coverage>".getBytes(StandardCharsets.UTF_8);
    private final ConcurrentLinkedQueue<Response> responses = new ConcurrentLinkedQueue<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private long nanos;
    private HttpServer server;
    private URI api;
    private Response fallback = new Response(200, "{}", 0);

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                Map.copyOf(exchange.getRequestHeaders()), exchange.getRequestBody().readAllBytes()));
            Response response = responses.poll();
            if (response == null) response = fallback;
            try {
                Thread.sleep(response.delayMillis());
                byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(response.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        api = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach void stopServer() { server.stop(0); }

    @Test void sendsActualCompressedXmlAndPrHeadWithNativeHeaders() throws Exception {
        reply(201, "{\"id\":17}"); reply(200, "{\"processing_status\":\"succeeded\"}");
        var receipt = upload(client(), 1087L, null);
        assertEquals("PROCESSED", receipt.outcome());
        assertEquals(201, receipt.uploadHttpStatus());
        assertEquals("17", receipt.reportId());
        assertEquals("succeeded", receipt.processingStatus());
        Request put = requests.getFirst();
        assertEquals("PUT", put.method());
        assertEquals("/repos/owner/repository/code-coverage/report", put.path());
        assertEquals(List.of("Bearer " + TOKEN), put.headers().get("Authorization"));
        assertEquals(List.of("application/vnd.github+json"), put.headers().get("Accept"));
        assertEquals(List.of("application/json"), put.headers().get("Content-type"));
        JsonNode body = JSON.readTree(put.body());
        assertEquals(5, body.size());
        assertEquals(COMMIT, body.path("commit_oid").asText());
        assertEquals("Java", body.path("language_name").asText());
        assertEquals("code-coverage/jacoco", body.path("label").asText());
        assertTrue(body.path("pull_request_number").isIntegralNumber());
        assertEquals(1087, body.path("pull_request_number").asInt());
        assertFalse(body.has("ref"));
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(body.path("coverage_report").asText())))) {
            assertArrayEquals(XML, gzip.readAllBytes());
        }
        assertEquals("GET", requests.get(1).method());
        assertEquals("/repos/owner/repository/code-coverage/reports/17", requests.get(1).path());
        assertEquals(List.of(5L), delays);
    }

    @Test void defaultBranchPayloadUsesRefWithoutPrNumber() throws Exception {
        reply(201, "{\"id\":\"report-17\"}"); reply(200, "{\"processing_status\":\"succeeded\"}");
        assertEquals("PROCESSED", upload(client(), null, "refs/heads/main").outcome());
        JsonNode body = JSON.readTree(requests.getFirst().body());
        assertEquals("refs/heads/main", body.path("ref").asText());
        assertFalse(body.has("pull_request_number"));
    }

    @Test void skippedStorageNeverPollsOrClaimsProcessedCoverage() throws Exception {
        reply(200, "{\"message\":\"commit is no longer latest\"}");
        var receipt = upload(client(), 1087L, null);
        assertEquals("SKIPPED", receipt.outcome());
        assertNull(receipt.reportId());
        assertNull(receipt.processingStatus());
        assertEquals(1, requests.size());
    }

    @ParameterizedTest @ValueSource(ints = {201, 202, 403, 500})
    void rejectedOrMalformedUploadIsNeverRetried(int status) throws Exception {
        reply(status, "{\"message\":\"rejected " + TOKEN + "\"}");
        var receipt = upload(client(), 1087L, null);
        assertEquals("FAILED", receipt.outcome());
        assertEquals(status, receipt.uploadHttpStatus());
        assertEquals(1, requests.size());
        assertFalse(receipt.toString().contains(TOKEN));
    }

    @Test void transientQueryFailureDoesNotDiscardTheAcceptedUpload() throws Exception {
        reply(201, "{\"id\":17}"); reply(503, "{}");
        reply(200, "{\"processing_status\":\"pending\"}");
        reply(200, "{\"processing_status\":\"processing\"}");
        reply(200, "{\"processing_status\":\"succeeded\"}");
        assertEquals("PROCESSED", upload(client(), 1087L, null).outcome());
        assertEquals(List.of(5L, 10L, 20L, 40L), delays);
        assertEquals(List.of("PUT", "GET", "GET", "GET", "GET"), requests.stream().map(Request::method).toList());
    }

    @Test void processingFailureRetainsErrorsAndRedactsCredentials() throws Exception {
        reply(201, "{\"id\":17}");
        reply(200, "{\"processing_status\":\"failed\",\"errors\":[\"unmapped source\",\"" + TOKEN + "\"]}");
        var receipt = upload(client(), 1087L, null);
        assertEquals("FAILED", receipt.outcome());
        assertEquals("failed", receipt.processingStatus());
        assertTrue(receipt.detail().contains("unmapped source"));
        assertFalse(receipt.toString().contains(TOKEN));
        assertEquals(2, requests.size());
    }

    @Test void queryClientErrorFailsImmediately() throws Exception {
        reply(201, "{\"id\":17}"); reply(404, "{\"message\":\"not found\"}");
        var receipt = upload(client(), 1087L, null);
        assertEquals("FAILED", receipt.outcome());
        assertTrue(receipt.detail().contains("404"));
        assertEquals(2, requests.size());
    }

    @ParameterizedTest @ValueSource(strings = {"not JSON", "{}", "{\"processing_status\":\"unknown\"}"})
    void unrecognizedProcessingCannotOutliveDeadline(String body) throws Exception {
        reply(201, "{\"id\":17}"); fallback = new Response(200, body, 0);
        var receipt = upload(client(), 1087L, null);
        assertEquals("FAILED", receipt.outcome());
        assertTrue(receipt.detail().contains("160"));
        assertEquals(List.of(5L, 10L, 20L, 40L, 60L, 25L), delays);
        assertEquals(6, requests.size(), "No GET may start once the deadline is reached");
    }

    @Test void individualQueryCannotOutliveRemainingDeadline() throws Exception {
        reply(201, "{\"id\":17}");
        responses.add(new Response(200, "{\"processing_status\":\"succeeded\"}", 250));
        var client = new NativeCodeCoverageClient(() -> nanos, delay ->
            nanos = nanos == 0 ? Duration.ofSeconds(160).toNanos() - Duration.ofMillis(5).toNanos() : nanos + delay.toNanos());
        assertEquals("FAILED", upload(client, 1087L, null).outcome());
    }

    @Test void interruptionStopsQueriesAndPreservesInterrupt() {
        reply(201, "{\"id\":17}");
        var client = new NativeCodeCoverageClient(() -> nanos, delay -> { throw new InterruptedException("cancelled"); });
        try {
            assertThrows(InterruptedException.class, () -> upload(client, 1087L, null));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, requests.size());
        } finally { Thread.interrupted(); }
    }

    @Test void ambiguousOrMissingContextCannotSendCoverage() {
        assertThrows(IllegalArgumentException.class, () -> upload(client(), null, null));
        assertThrows(IllegalArgumentException.class, () -> upload(client(), 1087L, "refs/heads/main"));
        assertThrows(IllegalArgumentException.class, () -> upload(client(), 0L, null));
        assertTrue(requests.isEmpty());
    }

    @Test void uploadConnectionFailureDoesNotBecomeSuccess() throws Exception {
        server.stop(0);
        var receipt = upload(client(), 1087L, null);
        assertEquals("FAILED", receipt.outcome());
        assertEquals(0, receipt.uploadHttpStatus());
    }

    private NativeCodeCoverageClient client() {
        return new NativeCodeCoverageClient(() -> nanos, delay -> { delays.add(delay.toSeconds()); nanos += delay.toNanos(); });
    }
    private NativeCodeCoverageClient.Receipt upload(NativeCodeCoverageClient client, Long pr, String ref) throws Exception {
        return client.upload(api, "owner/repository", TOKEN, COMMIT, pr, ref, XML);
    }
    private void reply(int status, String body) { responses.add(new Response(status, body, 0)); }
    private record Response(int status, String body, long delayMillis) { }
    private record Request(String method, String path, Map<String, List<String>> headers, byte[] body) { }
}
