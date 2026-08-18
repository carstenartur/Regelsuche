package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRevisionEvidence;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunInput;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunOutcome;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunPlan;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunWorkspace;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepresentationDiscoveryRunHttpContractTest {
    private static final String RUN_DIRECTORY_PROPERTY =
        "regelsuche.discovery.runs.directory";
    private static final String REPOSITORY_COMMIT =
        "0123456789abcdef0123456789abcdef01234567";

    private WebWorkbenchServer server;
    private Path runDirectory;

    @BeforeEach
    void start(@TempDir Path temporary) throws IOException {
        runDirectory = temporary.resolve("runs");
        System.setProperty(
            RUN_DIRECTORY_PROPERTY,
            runDirectory.toString()
        );
        server = new WebWorkbenchServer(
            "127.0.0.1",
            0,
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            WebSecurityConfig.none()
        );
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
        System.clearProperty(RUN_DIRECTORY_PROPERTY);
    }

    @Test
    void invalidRunDigestHasADedicatedJsonErrorCode() throws IOException {
        HttpURLConnection connection = get(
            "/api/discovery-runs/not-a-digest"
        );

        assertEquals(400, connection.getResponseCode());
        assertEquals(
            "application/json; charset=utf-8",
            connection.getHeaderField("Content-Type")
        );
        assertEquals(
            "{\"error\":true,\"code\":\"INVALID_RUN_DIGEST\","
                + "\"message\":\"run path must contain a lowercase "
                + "SHA-256 digest\"}",
            readBody(connection)
        );
    }

    @Test
    void runListQueryErrorsNameTheParameterAndItsContract()
            throws IOException {
        HttpURLConnection bounds = get("/api/discovery-runs?limit=0");
        assertEquals(400, bounds.getResponseCode());
        assertTrue(readBody(bounds).contains(
            "query parameter 'limit' must be between 1 and 500"
        ));

        HttpURLConnection type = get(
            "/api/discovery-runs?offset=not-an-integer"
        );
        assertEquals(400, type.getResponseCode());
        assertTrue(readBody(type).contains(
            "query parameter 'offset' must be an integer"
        ));
    }

    @Test
    void immutableRunConflictsUseTheTyped409Mapping() throws IOException {
        RepresentationDiscoveryRunWorkspace workspace = workspace();
        Files.createDirectories(runDirectory);
        Files.writeString(
            retainedPath(workspace.runId()),
            "{}",
            StandardCharsets.UTF_8
        );

        HttpURLConnection connection = post(
            "/api/discovery-runs",
            workspace.toCanonicalJson()
        );

        assertEquals(409, connection.getResponseCode());
        String body = readBody(connection);
        assertTrue(body.contains(
            "\"code\":\"IMMUTABLE_RUN_CONFLICT\""
        ));
        assertTrue(body.contains(
            "immutable run identity already contains different bytes"
        ));
    }

    @Test
    void runListDecodesOnlyTheRequestedPage() throws IOException {
        RepresentationDiscoveryRunWorkspace workspace = workspace();
        RepresentationDiscoveryRunWorkspace.retain(
            runDirectory,
            workspace
        );
        Files.writeString(
            runDirectory.resolve("f".repeat(64) + ".json"),
            "not canonical JSON",
            StandardCharsets.UTF_8
        );

        HttpURLConnection connection = get(
            "/api/discovery-runs?offset=0&limit=1"
        );

        assertEquals(200, connection.getResponseCode());
        String body = readBody(connection);
        assertTrue(body.contains("\"total\":2"));
        assertTrue(body.contains(workspace.runId()));
    }

    private HttpURLConnection get(String path) throws IOException {
        return open(path);
    }

    private HttpURLConnection post(String path, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open(path);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty(
            "Content-Type",
            "application/json"
        );
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        return connection;
    }

    private HttpURLConnection open(String path) throws IOException {
        URI uri = URI.create(
            "http://127.0.0.1:" + server.boundPort() + path
        );
        HttpURLConnection connection =
            (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(15_000);
        return connection;
    }

    private static String readBody(HttpURLConnection connection)
            throws IOException {
        InputStream stream = connection.getResponseCode() >= 400
            ? connection.getErrorStream()
            : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (stream) {
            return new String(
                stream.readAllBytes(),
                StandardCharsets.UTF_8
            );
        }
    }

    private Path retainedPath(String runId) {
        return runDirectory.resolve(
            runId.substring("sha256:".length()) + ".json"
        );
    }

    private static RepresentationDiscoveryRunWorkspace workspace() {
        return RepresentationDiscoveryRunWorkspace.create(
            RepresentationDiscoveryRunInput.expression(
                "sin(x)^2 + cos(x)^2",
                List.of()
            ),
            RepresentationDiscoveryRunPlan.create(
                RepresentationDiscoveryInformationBoundary.Track
                    .R2_CATALOG_BLIND_POST_HOC_BRIDGE,
                sha("boundary"),
                sha("inventory"),
                sha("selection"),
                sha("catalog"),
                "target-free-breadth-first/v1",
                "pareto-archive/v1",
                "representation-discovery/v1",
                sha("budget"),
                42,
                List.of("internal:java25")
            ),
            RepresentationDiscoveryRunOutcome.created(),
            RepresentationDiscoveryRunWorkspace.notProducedArtifacts(),
            RepresentationDiscoveryRevisionEvidence.create(
                REPOSITORY_COMMIT,
                "Regelsuche-workbench/test"
            )
        );
    }

    private static String sha(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 is unavailable",
                exception
            );
        }
    }
}
