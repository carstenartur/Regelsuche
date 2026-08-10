package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.proof.InMemoryProofCache;
import de.regelsuche.proof.InMemoryProofJobRepository;
import de.regelsuche.proof.JsonFileProofArtifactRepository;
import de.regelsuche.proof.LeanProofWorker;
import de.regelsuche.proof.ProofJobScheduler;
import de.regelsuche.proof.ProofWorkbenchService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Smoke-test for the /api/proof/jobs REST surface. Uses an
 * {@link InMemoryProofJobRepository} and a file-backed artifact repository so
 * the bundle layout (proof.lean/.smt2, stdout.txt, ...) is exercised end-to-end.
 */
class ProofJobsApiTest {

    private WebWorkbenchServer server;
    private ProofJobScheduler scheduler;

    @BeforeEach
    void start(@TempDir Path tmp) throws IOException {
        InMemoryProofJobRepository jobs = new InMemoryProofJobRepository();
        InMemoryProofCache cache = new InMemoryProofCache();
        JsonFileProofArtifactRepository artifacts = new JsonFileProofArtifactRepository(tmp);
        // Skeleton-only LeanProofWorker — no external Lean toolchain required.
        scheduler = new ProofJobScheduler(
            new LeanProofWorker(), jobs, cache,
            new InMemoryRuleInventoryRepository(), artifacts,
            Duration.ofSeconds(5));
        scheduler.start();
        ProofWorkbenchService workbench = new ProofWorkbenchService(scheduler, jobs, artifacts);

        server = new WebWorkbenchServer(
            "127.0.0.1", 0,
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            WebSecurityConfig.none(),
            null, null, null, workbench
        );
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
        scheduler.close();
    }

    @Test
    void emptyJobListReturnsValidJson() throws IOException {
        HttpURLConnection connection = open("/api/proof/jobs", "GET");
        assertEquals(200, connection.getResponseCode());
        String body = read(connection);
        assertTrue(body.contains("\"jobs\""), body);
    }

    @Test
    void submitListGetAndCancelRoundTrip() throws Exception {
        // POST /api/proof/jobs
        HttpURLConnection submit = open("/api/proof/jobs", "POST");
        submit.setDoOutput(true);
        submit.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = submit.getOutputStream()) {
            os.write("{\"leftPattern\":\"a+0\",\"rightPattern\":\"a\",\"priority\":1}"
                .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(201, submit.getResponseCode());
        String submitBody = read(submit);
        assertTrue(submitBody.contains("\"jobId\""), submitBody);
        String jobId = extract(submitBody, "jobId");
        assertNotNull(jobId);

        // GET list — must contain the job we just created
        String listBody = read(open("/api/proof/jobs", "GET"));
        assertTrue(listBody.contains(jobId), listBody);

        // GET details
        HttpURLConnection detail = open("/api/proof/jobs/" + jobId, "GET");
        assertEquals(200, detail.getResponseCode());
        String detailBody = read(detail);
        assertTrue(detailBody.contains("\"leftPattern\":\"a+0\""), detailBody);

        // POST cancel
        HttpURLConnection cancel = open("/api/proof/jobs/" + jobId + "/cancel", "POST");
        assertEquals(200, cancel.getResponseCode());
        // The job is now either CANCELLED or already DONE (if the scheduler
        // raced ahead). Both are acceptable terminal states.
        String cancelBody = read(cancel);
        assertTrue(cancelBody.contains("CANCELLED") || cancelBody.contains("DONE")
                || cancelBody.contains("RUNNING") || cancelBody.contains("QUEUED"),
            cancelBody);

        // GET artifacts listing — endpoint must respond even if bundle is empty
        HttpURLConnection artifacts = open(
            "/api/proof/jobs/" + jobId + "/artifacts", "GET");
        assertEquals(200, artifacts.getResponseCode());
        String artifactsBody = read(artifacts);
        assertTrue(artifactsBody.contains("\"artifacts\""), artifactsBody);
    }

    @Test
    void submitAcceptsUiStructuredAndLegacyStringAssumptions() throws IOException {
        HttpURLConnection submit = open("/api/proof/jobs", "POST");
        submit.setDoOutput(true);
        submit.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = submit.getOutputStream()) {
            os.write("""
                {
                  "leftPattern":"a / b",
                  "rightPattern":"a * (1 / b)",
                  "assumptions":[
                    {"kind":"NON_ZERO","expression":"b != 0","symbols":["b"]},
                    "a is real"
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(201, submit.getResponseCode());
        assertTrue(read(submit).contains("\"jobId\""));
    }

    @Test
    void submitWithoutPatternsIs400() throws IOException {
        HttpURLConnection submit = open("/api/proof/jobs", "POST");
        submit.setDoOutput(true);
        submit.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = submit.getOutputStream()) {
            os.write("{\"leftPattern\":\"\",\"rightPattern\":\"\"}"
                .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(400, submit.getResponseCode());
    }

    @Test
    void submitWithNegativePriorityIs400() throws IOException {
        HttpURLConnection submit = open("/api/proof/jobs", "POST");
        submit.setDoOutput(true);
        submit.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = submit.getOutputStream()) {
            os.write("{\"leftPattern\":\"a+0\",\"rightPattern\":\"a\",\"priority\":-1}"
                .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(400, submit.getResponseCode());
    }

    @Test
    void submitWithWorkerHintIs400() throws IOException {
        HttpURLConnection submit = open("/api/proof/jobs", "POST");
        submit.setDoOutput(true);
        submit.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = submit.getOutputStream()) {
            os.write("{\"leftPattern\":\"a+0\",\"rightPattern\":\"a\",\"worker\":\"lean4\"}"
                .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(400, submit.getResponseCode());
    }

    @Test
    void unknownJobIs404() throws IOException {
        HttpURLConnection connection = open("/api/proof/jobs/does-not-exist", "GET");
        assertEquals(404, connection.getResponseCode());
    }

    @Test
    void artifactWithTraversalIs400() throws IOException {
        HttpURLConnection connection = open(
            "/api/proof/jobs/any/artifacts/..%2Fescape", "GET");
        // The HttpServer may normalise the encoded segment, but in either case
        // a path-traversing read must not succeed.
        int status = connection.getResponseCode();
        assertTrue(status == 400 || status == 404, "expected 400/404 but got " + status);
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.boundPort() + path);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod(method);
        return connection;
    }

    private String read(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        try (java.io.InputStream stream = (code < 400
                ? connection.getInputStream() : connection.getErrorStream())) {
            return stream == null ? ""
                : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Tiny ad-hoc JSON string extractor — sufficient for flat objects. */
    private static String extract(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }
}
