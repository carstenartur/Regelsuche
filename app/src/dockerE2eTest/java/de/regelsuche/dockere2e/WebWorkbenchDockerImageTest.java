package de.regelsuche.dockere2e;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-image-based integration tests that verify the real container correctly
 * serves static assets including the {@code /vendor/} path that caused a 404
 * regression (May 2026) because {@code handleStatic} did not route it.
 *
 * <p>These tests are the definitive regression guard: they will fail immediately
 * if {@code handleStatic} loses the {@code /vendor/} route again.</p>
 *
 * <p>The tests skip automatically when Docker is not available on the host.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class WebWorkbenchDockerImageTest {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(WebWorkbenchDockerImageTest.class);

    private static final String PROJECT_ROOT =
        System.getProperty("regelsuche.projectRoot",
            Path.of("").toAbsolutePath().toString());

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> CONTAINER =
        new GenericContainer<>(
            new ImageFromDockerfile()
                .withFileFromPath(".", Path.of(PROJECT_ROOT)))
            // Static HTTP behaviour is independent of durable persistence. Avoid
            // coupling this image-level smoke test to a writable Docker volume.
            .withEnv("REGELSUCHE_PERSISTENCE_MODE", "IN_MEMORY")
            .withLogConsumer(new Slf4jLogConsumer(LOG))
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/").forStatusCode(200));

    private HttpClient client() {
        return HttpClient.newHttpClient();
    }

    private String baseUrl() {
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(8080);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .build();
        return client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .build();
        return client().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void indexHtmlIsServed() throws Exception {
        HttpResponse<String> resp = get("/");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type")
            .orElse("").startsWith("text/html"), "Content-Type should be text/html");
        assertTrue(resp.body().contains("vendor/katex/katex.min.css"),
            "index.html should reference vendor/katex/katex.min.css");
    }

    @Test
    void katexCssIsServed() throws Exception {
        HttpResponse<String> resp = get("/vendor/katex/katex.min.css");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type")
            .orElse("").startsWith("text/css"), "Content-Type should be text/css");
        assertTrue(resp.body().contains(".katex"),
            "katex.min.css body should contain .katex");
    }

    @Test
    void katexJsIsServed() throws Exception {
        HttpResponse<byte[]> resp = getBytes("/vendor/katex/katex.min.js");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type")
            .orElse("").startsWith("application/javascript"),
            "Content-Type should be application/javascript");
        assertTrue(resp.body().length > 100 * 1024,
            "katex.min.js should be > 100 KB, got " + resp.body().length + " bytes");
    }

    @Test
    void katexAutoRenderJsIsServed() throws Exception {
        HttpResponse<String> resp = get("/vendor/katex/contrib/auto-render.min.js");
        assertEquals(200, resp.statusCode());
    }

    @Test
    void katexFontIsServed() throws Exception {
        HttpResponse<byte[]> resp = getBytes("/vendor/katex/fonts/KaTeX_Main-Regular.woff2");
        assertEquals(200, resp.statusCode());
        assertEquals("font/woff2",
            resp.headers().firstValue("Content-Type").orElse(""),
            "Content-Type should be font/woff2");
    }

    @Test
    void cytoscapeJsIsServed() throws Exception {
        HttpResponse<String> resp = get("/vendor/cytoscape/cytoscape.min.js");
        assertEquals(200, resp.statusCode());
    }

    @Test
    void appJsIsServed() throws Exception {
        HttpResponse<String> resp = get("/app.js");
        assertEquals(200, resp.statusCode());
    }

    @Test
    void pathTraversalIsRejected() throws Exception {
        HttpResponse<String> resp = get("/vendor/..%2F..%2F..%2F..%2Fetc%2Fpasswd");
        assertTrue(resp.statusCode() >= 400,
            "Path traversal should be rejected with 4xx, got " + resp.statusCode());
    }

    @Test
    void rawPathTraversalIsRejected() throws Exception {
        HttpResponse<String> resp = get("/vendor/../../../../etc/passwd");
        assertTrue(resp.statusCode() >= 400,
            "Path traversal should be rejected with 4xx, got " + resp.statusCode());
    }
}
