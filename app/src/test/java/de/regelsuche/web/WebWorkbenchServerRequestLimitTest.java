package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.json.JsonReader;
import de.regelsuche.proof.InMemoryProofCache;
import de.regelsuche.proof.InMemoryProofJobRepository;
import de.regelsuche.proof.JsonFileProofArtifactRepository;
import de.regelsuche.proof.LeanProofWorker;
import de.regelsuche.proof.ProofJobScheduler;
import de.regelsuche.proof.ProofWorkbenchService;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.validation.CandidateProofStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the common request-body limit across every documented JSON POST surface. */
class WebWorkbenchServerRequestLimitTest {
    private static final int REQUEST_LIMIT = 1024;
    private static final String PAYLOAD_TOO_LARGE =
        "{\"error\":true,\"code\":\"PAYLOAD_TOO_LARGE\","
            + "\"message\":\"request body exceeds configured limit\",\"limitBytes\":1024}";

    private WebWorkbenchServer server;
    private ProofJobScheduler scheduler;

    @BeforeEach
    void start(@TempDir Path tempDir) throws IOException {
        InMemoryExpressionGraphStore graphStore = new InMemoryExpressionGraphStore();
        graphStore.saveDiscoveredTransformation(sampleDerivation());

        InMemoryProofJobRepository jobs = new InMemoryProofJobRepository();
        JsonFileProofArtifactRepository artifacts = new JsonFileProofArtifactRepository(tempDir);
        scheduler = new ProofJobScheduler(
            new LeanProofWorker(),
            jobs,
            new InMemoryProofCache(),
            new InMemoryRuleInventoryRepository(),
            artifacts,
            Duration.ofSeconds(5)
        );
        scheduler.start();
        ProofWorkbenchService workbench = new ProofWorkbenchService(scheduler, jobs, artifacts);

        WebSecurityConfig security = WebSecurityConfig.builder()
            .maxRequestBytes(REQUEST_LIMIT)
            .build();
        server = new WebWorkbenchServer(
            "127.0.0.1",
            0,
            graphStore,
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            security,
            null,
            null,
            null,
            workbench
        );
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
        if (scheduler != null) {
            scheduler.close();
        }
    }

    @Test
    void acceptsExactBoundaryAndRejectsFixedAndChunkedOversizeAcrossJsonPostSurface()
        throws IOException {
        assertEquals(documentedJsonPostPaths(), endpointTemplates(),
            "the executable request-limit matrix must cover every OpenAPI JSON body operation");

        for (Endpoint endpoint : endpoints()) {
            HttpURLConnection exact = postFixed(
                endpoint.path(),
                jsonBody(REQUEST_LIMIT, endpoint.fields())
            );
            int exactStatus = exact.getResponseCode();
            String exactBody = readBody(exact);
            assertTrue(
                exactStatus >= 200
                    && exactStatus < 500
                    && exactStatus != 404
                    && exactStatus != 405
                    && exactStatus != 413,
                () -> endpoint.path() + " exact-boundary response was "
                    + exactStatus + ": " + exactBody
            );
            assertFalse(
                exactBody.contains("\"code\":\"PAYLOAD_TOO_LARGE\""),
                () -> endpoint.path() + " rejected the exact boundary: " + exactBody
            );

            assertPayloadTooLarge(postFixed(
                endpoint.path(),
                jsonBody(REQUEST_LIMIT + 1, endpoint.fields())
            ), endpoint.path() + " fixed");
            assertPayloadTooLarge(postChunked(
                endpoint.path(),
                jsonBody(REQUEST_LIMIT + 1, endpoint.fields())
            ), endpoint.path() + " chunked");
        }

        HttpURLConnection followUp = open("/api/paths");
        assertEquals(200, followUp.getResponseCode());
        assertTrue(readBody(followUp).contains("sample-derivation-id"));
    }

    @Test
    void rejectsAmbiguousOrMalformedJsonAndKeeps413Authoritative() throws IOException {
        HttpURLConnection duplicate = postFixed(
            "/api/search",
            "{\"expression\":\"x\",\"expression\":\"y\"}"
                .getBytes(StandardCharsets.UTF_8)
        );
        assertEquals(400, duplicate.getResponseCode());
        assertEquals("invalid JSON request body", readBody(duplicate));

        HttpURLConnection trailing = postChunked(
            "/api/search",
            "{\"expression\":\"x\"} {\"other\":true}"
                .getBytes(StandardCharsets.UTF_8)
        );
        assertEquals(400, trailing.getResponseCode());
        assertEquals("invalid JSON request body", readBody(trailing));

        HttpURLConnection wrongType = postFixed(
            "/api/search",
            "{\"expression\":7}".getBytes(StandardCharsets.UTF_8)
        );
        assertEquals(400, wrongType.getResponseCode());
        assertEquals("invalid JSON request body", readBody(wrongType));

        byte[] prefix = "{\"expression\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        byte[] malformedUtf8 = new byte[prefix.length + 2 + suffix.length];
        System.arraycopy(prefix, 0, malformedUtf8, 0, prefix.length);
        malformedUtf8[prefix.length] = (byte) 0xC3;
        malformedUtf8[prefix.length + 1] = 0x28;
        System.arraycopy(suffix, 0, malformedUtf8, prefix.length + 2, suffix.length);
        HttpURLConnection malformed = postChunked("/api/search", malformedUtf8);
        assertEquals(400, malformed.getResponseCode());
        assertEquals("invalid JSON request body", readBody(malformed));

        HttpURLConnection radarNullContext = postFixed(
            "/api/rule-radar/inspect",
            "{\"expression\":\"x\",\"context\":{\"goalExpression\":null}}"
                .getBytes(StandardCharsets.UTF_8)
        );
        assertEquals(200, radarNullContext.getResponseCode());
        assertFalse(readBody(radarNullContext).contains("RADAR_FAILURE"));

        HttpURLConnection radarDuplicate = postFixed(
            "/api/rule-radar/inspect",
            "{\"expression\":\"x\",\"expression\":\"y\"}"
                .getBytes(StandardCharsets.UTF_8)
        );
        assertEquals(400, radarDuplicate.getResponseCode());
        assertEquals(
            "{\"error\":true,\"code\":\"INVALID_JSON\","
                + "\"message\":\"invalid JSON request body\"}",
            readBody(radarDuplicate)
        );

        byte[] oversizedMalformed = ("{\"expression\":\""
            + "x".repeat(REQUEST_LIMIT * 2)).getBytes(StandardCharsets.UTF_8);
        assertPayloadTooLarge(
            postChunked("/api/search", oversizedMalformed),
            "oversized malformed JSON"
        );

        HttpURLConnection followUp = open("/api/paths");
        assertEquals(200, followUp.getResponseCode());
        assertTrue(readBody(followUp).contains("sample-derivation-id"));
    }

    @Test
    void rejectsWrongKnownFieldTypesAcrossEveryTypedJsonPostSurface()
            throws IOException {
        List<InvalidRequest> workbenchRequests = List.of(
            new InvalidRequest("/api/search", "{\"expression\":7}"),
            new InvalidRequest("/api/discover", "{\"min\":\"1\"}"),
            new InvalidRequest("/api/inventory", "{\"json\":1}"),
            new InvalidRequest(
                "/api/inspect/tree/apply",
                "{\"matchIndex\":\"0\"}"
            ),
            new InvalidRequest(
                "/api/didactic/step-check",
                "{\"currentExpression\":1}"
            ),
            new InvalidRequest(
                "/api/didactic/hint/sample-derivation-id",
                "{\"pedagogyProfile\":false}"
            ),
            new InvalidRequest(
                "/api/proof-bridge",
                "{\"assumptions\":[{\"expression\":7}]}"
            ),
            new InvalidRequest(
                "/api/proof/jobs",
                "{\"assumptions\":[7]}"
            )
        );
        for (InvalidRequest request : workbenchRequests) {
            HttpURLConnection connection = postFixed(
                request.path(),
                request.body().getBytes(StandardCharsets.UTF_8)
            );
            assertEquals(400, connection.getResponseCode(), request.path());
            assertEquals(
                "invalid JSON request body",
                readBody(connection),
                request.path()
            );
        }

        List<InvalidRequest> radarRequests = List.of(
            new InvalidRequest(
                "/api/rule-radar/inspect",
                "{\"expression\":7}"
            ),
            new InvalidRequest(
                "/api/rule-radar/apply",
                "{\"candidateId\":false}"
            ),
            new InvalidRequest(
                "/api/rule-radar/search",
                "{\"maxDepth\":\"4\"}"
            )
        );
        String expectedRadarError = "{\"error\":true,"
            + "\"code\":\"INVALID_JSON\","
            + "\"message\":\"invalid JSON request body\"}";
        for (InvalidRequest request : radarRequests) {
            HttpURLConnection connection = postChunked(
                request.path(),
                request.body().getBytes(StandardCharsets.UTF_8)
            );
            assertEquals(400, connection.getResponseCode(), request.path());
            assertEquals(expectedRadarError, readBody(connection), request.path());
        }
    }

    private void assertPayloadTooLarge(HttpURLConnection connection, String owner) throws IOException {
        assertEquals(413, connection.getResponseCode(), owner);
        assertEquals("application/json; charset=utf-8", connection.getHeaderField("Content-Type"), owner);
        assertEquals("no-store", connection.getHeaderField("Cache-Control"), owner);
        assertEquals(PAYLOAD_TOO_LARGE, readBody(connection), owner);
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
        URI uri = URI.create("http://127.0.0.1:" + server.boundPort() + path);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(15000);
        return connection;
    }

    private byte[] jsonBody(int length, String fields) {
        String prefix = "{" + fields + (fields.isBlank() ? "" : ",") + "\"padding\":\"";
        String suffix = "\"}";
        int paddingLength = length - prefix.getBytes(StandardCharsets.UTF_8).length
            - suffix.getBytes(StandardCharsets.UTF_8).length;
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

    private static List<Endpoint> endpoints() {
        return List.of(
            new Endpoint("/api/search", "/api/search", "\"expression\":\"\""),
            new Endpoint("/api/discover", "/api/discover", "\"min\":1,\"max\":0"),
            new Endpoint("/api/inventory", "/api/inventory", "\"json\":\"\""),
            new Endpoint("/api/discovery-runs", "/api/discovery-runs", ""),
            new Endpoint("/api/inspect/tree/apply", "/api/inspect/tree/apply", ""),
            new Endpoint("/api/didactic/step-check", "/api/didactic/step-check", ""),
            new Endpoint(
                "/api/didactic/hint/{pathId}",
                "/api/didactic/hint/sample-derivation-id",
                "\"currentExpression\":\"a*(b+c)\",\"pedagogyProfile\":\"SCHOOL\""
            ),
            new Endpoint("/api/proof-bridge", "/api/proof-bridge", ""),
            new Endpoint("/api/proof/jobs", "/api/proof/jobs", ""),
            new Endpoint("/api/rule-radar/inspect", "/api/rule-radar/inspect", "\"expression\":\"x\""),
            new Endpoint("/api/rule-radar/apply", "/api/rule-radar/apply", ""),
            new Endpoint("/api/rule-radar/search", "/api/rule-radar/search", "\"expression\":\"x\"")
        );
    }

    private static Set<String> endpointTemplates() {
        return endpoints().stream()
            .map(Endpoint::pathTemplate)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> documentedJsonPostPaths() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/web/openapi/openapi.json")) {
            if (stream == null) {
                throw new IOException("packaged OpenAPI specification is missing");
            }
            Map<String, Object> document = new JsonReader(
                new String(stream.readAllBytes(), StandardCharsets.UTF_8)
            ).readObject();
            Set<String> pathsWithBodies = new LinkedHashSet<>();
            for (Map.Entry<String, Object> pathEntry : object(document.get("paths")).entrySet()) {
                Map<String, Object> post = object(object(pathEntry.getValue()).get("post"));
                if (!object(post.get("requestBody")).isEmpty()) {
                    pathsWithBodies.add(pathEntry.getKey());
                }
            }
            return pathsWithBodies;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static DiscoveredTransformation sampleDerivation() {
        TransformationStep step = new TransformationStep(
            0,
            "a*(b + c)",
            "a*b + a*c",
            "ast_distribute_left_add",
            RewriteKind.EXPAND,
            10,
            12,
            true,
            "Distributivgesetz angewandt"
        );
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
            "hash"
        );
    }

    private record InvalidRequest(String path, String body) {
    }

    private record Endpoint(
        String pathTemplate,
        String path,
        String fields
    ) {
    }
}
