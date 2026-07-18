package de.regelsuche.dockere2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the real {@code Dockerfile.proof} image without relying on GitHub
 * Actions shell orchestration, fixed host ports or manually managed containers.
 */
@Testcontainers(disabledWithoutDocker = true)
class ProofDockerImageIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Path PROJECT_ROOT = Path.of(System.getProperty(
        "regelsuche.projectRoot",
        Path.of("").toAbsolutePath().toString()
    )).toAbsolutePath().normalize();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> PROOF_IMAGE = new GenericContainer<>(
        new ImageFromDockerfile()
            .withFileFromPath(".", PROJECT_ROOT)
            .withDockerfilePath("./Dockerfile.proof")
    )
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
    void proofWorkbenchAcceptsAndListsAJob() throws Exception {
        HttpResponse<String> initial = send(HttpRequest.newBuilder()
            .uri(uri("/api/proof/jobs"))
            .GET()
            .build());
        assertEquals(200, initial.statusCode());

        HttpResponse<String> submitted = send(HttpRequest.newBuilder()
            .uri(uri("/api/proof/jobs"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"leftPattern\":\"a+0\",\"rightPattern\":\"a\",\"priority\":0}"
            ))
            .build());
        assertTrue(
            submitted.statusCode() >= 200 && submitted.statusCode() < 300,
            () -> "proof submission returned " + submitted.statusCode()
                + ": " + submitted.body()
        );

        JsonNode submittedDocument = JSON.readTree(submitted.body());
        String jobId = submittedDocument.path("jobId").asText();
        assertTrue(!jobId.isBlank(), submitted::body);

        HttpResponse<String> listed = send(HttpRequest.newBuilder()
            .uri(uri("/api/proof/jobs"))
            .GET()
            .build());
        assertEquals(200, listed.statusCode());

        JsonNode listedDocument = JSON.readTree(listed.body());
        boolean listedJobFound = listedDocument.path("jobs").findValuesAsText("id")
            .contains(jobId);
        assertTrue(listedJobFound,
            () -> "submitted job " + jobId + " missing from list: " + listed.body());
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
