package de.regelsuche.quality.coverage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.zip.GZIPOutputStream;

/** Native protocol: actions/upload-code-coverage at 2b21a77928be8d5168c2b9581a67f2adbebacc52. */
public final class NativeCodeCoverageClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DEADLINE_NANOS = Duration.ofSeconds(160).toNanos();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private final LongSupplier clock;
    private final Sleeper sleeper;

    @FunctionalInterface interface Sleeper { void sleep(Duration delay) throws InterruptedException; }
    public record Receipt(String outcome, int uploadHttpStatus, String reportId, String processingStatus, String detail) { }
    public NativeCodeCoverageClient() { this(System::nanoTime, Thread::sleep); }
    NativeCodeCoverageClient(LongSupplier clock, Sleeper sleeper) { this.clock = clock; this.sleeper = sleeper; }

    public Receipt upload(URI api, String repository, String token, String commit, Long pullRequest, String ref, byte[] cobertura)
            throws IOException, InterruptedException {
        boolean hasRef = ref != null && !ref.isBlank();
        if (api == null || !validApi(api) || repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
                || token == null || token.isBlank() || commit == null || !commit.matches("[0-9a-fA-F]{40}")
                || (pullRequest != null) == hasRef || (pullRequest != null && pullRequest <= 0)
                || (hasRef && !ref.startsWith("refs/heads/")) || cobertura == null || cobertura.length == 0) {
            throw new IllegalArgumentException("Invalid native coverage upload context");
        }
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) { gzip.write(cobertura); }
        var payload = JSON.createObjectNode().put("commit_oid", commit)
            .put("coverage_report", Base64.getEncoder().encodeToString(compressed.toByteArray()))
            .put("language_name", "Java").put("label", "code-coverage/jacoco");
        if (pullRequest != null) payload.put("pull_request_number", pullRequest); else payload.put("ref", ref);
        String base = api.toString().replaceAll("/+$", "") + "/repos/" + repository + "/code-coverage/";
        HttpClient http = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            HttpResponse<byte[]> uploaded;
            try { uploaded = send(http, URI.create(base + "report"), token, JSON.writeValueAsBytes(payload), REQUEST_TIMEOUT); }
            catch (IOException exception) { return receipt("FAILED", 0, null, null, exception.getMessage(), token); }
            int code = uploaded.statusCode();
            JsonNode body = object(uploaded.body());
            JsonNode idNode = body.path("id");
            String id = idNode.isTextual() || idNode.isIntegralNumber() ? idNode.asText() : null;
            if (id != null && (id.isBlank() || id.equals("0"))) id = null;
            if (code == 200 && id == null)
                return receipt("SKIPPED", code, null, null, body.path("message").asText("Coverage storage skipped: no report id"), token);
            if (code < 200 || code >= 300 || id == null)
                return receipt("FAILED", code, id, null, "Upload HTTP " + code + ": " + body.path("message").asText("Missing report id"), token);
            URI statusUri = URI.create(base + "reports/" + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20"));
            long started = clock.getAsLong();
            long delay = Duration.ofSeconds(5).toNanos();
            String status = null;
            for (long remaining; (remaining = DEADLINE_NANOS - (clock.getAsLong() - started)) > 0;) {
                sleeper.sleep(Duration.ofNanos(Math.min(delay, remaining)));
                remaining = DEADLINE_NANOS - (clock.getAsLong() - started);
                if (remaining <= 0) break;
                HttpResponse<byte[]> checked = null;
                try { checked = send(http, statusUri, token, null, Duration.ofNanos(Math.min(REQUEST_TIMEOUT.toNanos(), remaining))); }
                catch (IOException ignored) { /* Transient status queries remain bounded by the processing deadline. */ }
                if (checked != null) {
                    JsonNode state = object(checked.body());
                    int statusCode = checked.statusCode();
                    if (statusCode >= 400 && statusCode < 500)
                        return receipt("FAILED", code, id, status, "Processing query HTTP " + statusCode + ": " + state.path("message").asText(), token);
                    if (statusCode >= 200 && statusCode < 300) {
                        status = state.path("processing_status").asText(null);
                        if ("succeeded".equals(status)) return receipt("PROCESSED", code, id, status, null, token);
                        if ("failed".equals(status)) return receipt("FAILED", code, id, status, "Processing failed: " + state.path("errors"), token);
                    }
                }
                delay = Math.min(delay * 2, Duration.ofSeconds(60).toNanos());
            }
            return receipt("FAILED", code, id, status, "Timed out waiting 160 seconds for coverage processing", token);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } finally { http.shutdownNow(); }
    }

    private static HttpResponse<byte[]> send(HttpClient http, URI uri, String token, byte[] payload, Duration timeout)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri).timeout(timeout)
            .header("Authorization", "Bearer " + token).header("Accept", "application/vnd.github+json");
        if (payload == null) request.GET();
        else request.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofByteArray(payload));
        var response = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        try { return response.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        catch (TimeoutException exception) { response.cancel(true); throw new IOException("Native coverage request timed out", exception); }
        catch (ExecutionException exception) { throw new IOException("Native coverage request failed", exception.getCause()); }
        catch (InterruptedException exception) { response.cancel(true); throw exception; }
    }

    private static JsonNode object(byte[] body) {
        try { JsonNode parsed = JSON.readTree(body); if (parsed != null && parsed.isObject()) return parsed; }
        catch (IOException ignored) { /* Malformed processing responses cannot prove success. */ }
        return JSON.createObjectNode();
    }

    private static Receipt receipt(String outcome, int code, String id, String status, String detail, String token) {
        return new Receipt(outcome, code, redact(id, token), redact(status, token), redact(detail, token));
    }
    private static String redact(String value, String token) { return value == null ? null : value.replace(token, "[redacted]"); }

    private static boolean validApi(URI api) {
        return api.getHost() != null && api.getUserInfo() == null && api.getQuery() == null && api.getFragment() == null
            && ("https".equals(api.getScheme()) || ("http".equals(api.getScheme()) && "127.0.0.1".equals(api.getHost())));
    }
}
