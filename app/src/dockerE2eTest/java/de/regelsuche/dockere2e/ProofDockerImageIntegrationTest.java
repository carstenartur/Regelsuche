package de.regelsuche.dockere2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the real {@code Dockerfile.proof} image without relying on GitHub
 * Actions shell orchestration, fixed host ports or manually managed containers.
 */
@Testcontainers(disabledWithoutDocker = true)
class ProofDockerImageIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration PROOF_TIMEOUT = Duration.ofSeconds(90);

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> PROOF_IMAGE =
        new GenericContainer<>(RegelsucheDockerImages.PROOF)
            .withEnv("REGELSUCHE_PERSISTENCE_MODE", "IN_MEMORY")
            .withLogConsumer(frame -> System.err.print(frame.getUtf8String()))
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/api/proof/jobs").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(15));

    @Test
    void containsZ3() throws Exception {
        assertSuccessfulVersionCommand("z3", "--version", "z3");
    }

    @Test
    void containsCvc5() throws Exception {
        assertSuccessfulVersionCommand("cvc5", "--version", "cvc5");
    }

    @Test
    void proofWorkbenchCompletesSophieGermainWithSmtArtifacts() throws Exception {
        HttpResponse<String> initial = send(HttpRequest.newBuilder()
            .uri(uri("/api/proof/jobs"))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build());
        assertEquals(200, initial.statusCode());

        HttpResponse<String> submitted = send(HttpRequest.newBuilder()
            .uri(uri("/api/proof/jobs"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"leftPattern\":\"a^4 + 4*b^4\","
                    + "\"rightPattern\":"
                    + "\"(a^2 - 2*a*b + 2*b^2)*(a^2 + 2*a*b + 2*b^2)\","
                    + "\"priority\":0}"
            ))
            .build());
        assertTrue(
            submitted.statusCode() >= 200 && submitted.statusCode() < 300,
            () -> "proof submission returned " + submitted.statusCode()
                + ": " + submitted.body()
        );

        JsonNode submittedDocument = JSON.readTree(submitted.body());
        String jobId = submittedDocument.path("jobId").asText();
        assertFalse(jobId.isBlank(), submitted::body);

        JsonNode completed = awaitTerminalJob(jobId);
        assertEquals("DONE", completed.path("status").asText(), completed::toPrettyString);
        assertEquals(
            "FORMALLY_PROVED",
            completed.path("proofStatus").asText(),
            completed::toPrettyString);
        assertTrue(
            completed.path("workerId").asText().contains("smtlib2"),
            completed::toPrettyString);
        assertEquals("a^4 + 4*b^4", completed.path("leftPattern").asText());

        JsonNode artifacts = getJson("/api/proof/jobs/" + jobId + "/artifacts");
        var names = artifacts.path("artifacts").findValuesAsText("");
        assertTrue(names.contains("proof.smt2"), artifacts::toPrettyString);
        assertTrue(names.contains("metadata.json"), artifacts::toPrettyString);
        assertTrue(names.contains("stdout.txt"), artifacts::toPrettyString);
        assertTrue(names.contains("stderr.txt"), artifacts::toPrettyString);

        HttpResponse<String> proof = send(HttpRequest.newBuilder()
            .uri(uri("/api/proof/jobs/" + jobId + "/artifacts/proof.smt2"))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build());
        assertEquals(200, proof.statusCode(), proof::body);
        assertTrue(proof.body().contains("; left  : a^4 + 4*b^4"), proof::body);
        assertTrue(proof.body().contains("(* (* (* a a) a) a)"), proof::body);
        assertFalse(proof.body().contains("(pow "), proof::body);
        assertTrue(proof.body().contains("(check-sat)"), proof::body);

        JsonNode metadata = JSON.readTree(readArtifact(jobId, "metadata.json"));
        assertEquals("FORMALLY_PROVED", metadata.path("status").asText(),
            metadata::toPrettyString);
        assertEquals("smtlib2", metadata.path("tool").asText(),
            metadata::toPrettyString);
        assertEquals("", metadata.path("error").asText(),
            metadata::toPrettyString);
    }

    private static JsonNode awaitTerminalJob(String jobId) throws Exception {
        long deadline = System.nanoTime() + PROOF_TIMEOUT.toNanos();
        JsonNode latest = null;
        while (System.nanoTime() < deadline) {
            latest = getJson("/api/proof/jobs/" + jobId);
            String status = latest.path("status").asText();
            if ("DONE".equals(status)
                    || "FAILED".equals(status)
                    || "CANCELLED".equals(status)) {
                return latest;
            }
            Thread.sleep(250L);
        }
        JsonNode snapshot = latest;
        throw new AssertionError("proof job did not finish within " + PROOF_TIMEOUT
            + ": " + (snapshot == null ? "no response" : snapshot.toPrettyString()));
    }

    private static JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
            .uri(uri(path))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build());
        assertEquals(200, response.statusCode(), response::body);
        return JSON.readTree(response.body());
    }

    private static String readArtifact(String jobId, String name) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
            .uri(uri("/api/proof/jobs/" + jobId + "/artifacts/" + name))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build());
        assertEquals(200, response.statusCode(), response::body);
        return response.body();
    }

    private static void assertSuccessfulVersionCommand(
        String executable,
        String argument,
        String expectedText
    ) throws Exception {
        org.testcontainers.containers.Container.ExecResult result =
            PROOF_IMAGE.execInContainer(executable, argument);
        assertEquals(0, result.getExitCode(), result::getStderr);
        String output = result.getStdout() + result.getStderr();
        assertTrue(
            output.toLowerCase(java.util.Locale.ROOT).contains(expectedText),
            () -> executable + " version output was: " + output
        );
    }

    private static URI uri(String path) {
        return URI.create("http://" + PROOF_IMAGE.getHost() + ":"
            + PROOF_IMAGE.getMappedPort(8080) + path);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
