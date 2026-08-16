package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference;
import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole;
import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactStatus;
import de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRevisionEvidence;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunInput;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunOutcome;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunOutcome.TerminalState;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunPlan;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunWorkspace;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.json.JsonReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenApiRouteRegistryTest {

    @Test
    void loadsEveryCanonicalOperationAndContext() {
        OpenApiRouteRegistry registry = OpenApiRouteRegistry.load();

        assertEquals(58, registry.routes().size());
        assertEquals(23, registry.contexts().size());
        assertTrue(registry.contexts().containsAll(Set.of(
            "/api/search",
            "/api/paths",
            "/api/proof/jobs",
            "/api/didactic",
            "/api/rule-radar",
            "/api/discovery-runs"
        )));
    }

    @Test
    void resolvesStaticParameterizedAndEmbeddedSuffixTemplates() {
        OpenApiRouteRegistry registry = OpenApiRouteRegistry.load();

        assertAllowed(registry, "/api/paths", "/api/paths/compare", "GET", "comparePaths");
        assertAllowed(registry, "/api/paths", "/api/paths/path-42/replay", "GET", "replayPath");
        assertAllowed(registry, "/api/proof/jobs", "/api/proof/jobs/job-7/artifacts/stdout.txt", "GET",
            "downloadProofJobArtifact");
        assertAllowed(registry, "/api/didactic", "/api/didactic/export/worksheet/path-1.md", "GET",
            "downloadDidacticExport");
        assertAllowed(registry, "/api/inspect", "/api/inspect/tree", "GET",
            "inspectExpressionRules");
        assertAllowed(registry, "/api/inspect", "/api/inspect/tree/apply", "POST",
            "applyInspectedRule");
        assertAllowed(registry, "/api/exports", "/api/exports/cluster/cluster-1.md", "GET",
            "downloadClusterMarkdown");
        assertAllowed(registry, "/api/exports", "/api/exports/path/path-1.tex", "GET",
            "downloadPathLatex");
        assertAllowed(registry, "/api/exports", "/api/exports/identity/identity-1.md", "GET",
            "downloadIdentityMarkdown");
        assertAllowed(registry, "/api/discovery-runs", "/api/discovery-runs", "GET",
            "listRepresentationDiscoveryRuns");
        assertAllowed(registry, "/api/discovery-runs", "/api/discovery-runs", "POST",
            "retainRepresentationDiscoveryRun");
        assertAllowed(
            registry,
            "/api/discovery-runs",
            "/api/discovery-runs/" + "a".repeat(64),
            "GET",
            "getRepresentationDiscoveryRun"
        );
    }

    @Test
    void distinguishesWrongMethodsFromUnknownSubpaths() {
        OpenApiRouteRegistry registry = OpenApiRouteRegistry.load();

        OpenApiRouteRegistry.Match wrongMethod = registry.match("/api/paths", "/api/paths", "POST");
        assertEquals(OpenApiRouteRegistry.MatchStatus.METHOD_NOT_ALLOWED, wrongMethod.status());
        assertEquals(Set.of("GET"), wrongMethod.allowedMethods());

        OpenApiRouteRegistry.Match multiMethod = registry.match("/api/inventory", "/api/inventory", "DELETE");
        assertEquals(OpenApiRouteRegistry.MatchStatus.METHOD_NOT_ALLOWED, multiMethod.status());
        assertEquals(Set.of("GET", "POST"), multiMethod.allowedMethods());

        OpenApiRouteRegistry.Match unknown = registry.match(
            "/api/paths", "/api/paths/path-42/undocumented", "GET");
        assertEquals(OpenApiRouteRegistry.MatchStatus.NOT_FOUND, unknown.status());
    }

    @Test
    void acceptsOneOptionalTrailingSlashButNotAdditionalSegments() {
        OpenApiRouteRegistry registry = OpenApiRouteRegistry.load();

        assertAllowed(registry, "/api/search", "/api/search/", "POST", "startSearch");
        assertEquals(OpenApiRouteRegistry.MatchStatus.NOT_FOUND,
            registry.match("/api/search", "/api/search/extra", "POST").status());
        assertEquals(OpenApiRouteRegistry.MatchStatus.NOT_FOUND,
            registry.match("/api/didactic", "/api/didactic/export/worksheet/path-1.txt", "GET").status());
        assertEquals(OpenApiRouteRegistry.MatchStatus.NOT_FOUND,
            registry.match("/api/inspect", "/api/inspect", "POST").status());
        assertEquals(OpenApiRouteRegistry.MatchStatus.NOT_FOUND,
            registry.match("/api/inspect", "/api/inspect/apply", "POST").status());
        assertEquals(OpenApiRouteRegistry.MatchStatus.NOT_FOUND,
            registry.match(
                "/api/discovery-runs",
                "/api/discovery-runs/" + "a".repeat(64) + "/extra",
                "GET"
            ).status());
    }

    private static void assertAllowed(
        OpenApiRouteRegistry registry,
        String context,
        String path,
        String method,
        String operationId
    ) {
        OpenApiRouteRegistry.Match match = registry.match(context, path, method);
        assertEquals(OpenApiRouteRegistry.MatchStatus.ALLOWED, match.status());
        assertEquals(operationId, match.operationId());
    }
}

class RepresentationDiscoveryRunApiTest {
    private static final String CONTEXT = "/api/discovery-runs";
    private static final String REPOSITORY_COMMIT =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void retainsListsLoadsAndReopensCanonicalRuns(
        @TempDir Path directory
    ) throws Exception {
        RepresentationDiscoveryRunWorkspace workspace = workspace(42);
        try (Fixture fixture = new Fixture(
                directory,
                WebSecurityConfig.none())) {
            HttpResponse<String> created = fixture.send(
                "POST",
                CONTEXT,
                workspace.toCanonicalJson(),
                "application/json"
            );
            assertEquals(201, created.statusCode());
            assertEquals(workspace.toCanonicalJson(), created.body());
            assertEquals(
                CONTEXT + "/" + digest(workspace.runId()),
                created.headers().firstValue("Location").orElseThrow()
            );
            assertEquals(
                "\"" + digest(workspace.runId()) + "\"",
                created.headers().firstValue("ETag").orElseThrow()
            );

            HttpResponse<String> index = fixture.send(
                "GET",
                CONTEXT + "?offset=0&limit=1",
                "",
                null
            );
            assertEquals(200, index.statusCode());
            Map<String, Object> indexJson =
                new JsonReader(index.body()).readObject();
            assertEquals(
                "regelsuche.representation-discovery-run-index/v1",
                indexJson.get("schema")
            );
            assertEquals(1, number(indexJson.get("total")));
            assertEquals(1, ((List<?>) indexJson.get("runs")).size());
            assertTrue(index.body().contains(workspace.runId()));
            assertTrue(index.body().contains("SEARCH_GRAPH"));

            assertLoaded(fixture, workspace);
        }

        try (Fixture reopened = new Fixture(
                directory,
                WebSecurityConfig.none())) {
            assertLoaded(reopened, workspace);
        }
    }

    @Test
    void malformedMissingAndOversizedRequestsFailClosed(
        @TempDir Path directory
    ) throws Exception {
        RepresentationDiscoveryRunWorkspace workspace = workspace(7);
        try (Fixture fixture = new Fixture(
                directory,
                WebSecurityConfig.none())) {
            assertStatus(
                400,
                fixture.send(
                    "POST",
                    CONTEXT,
                    workspace.toCanonicalJson() + "\n",
                    "application/json"
                )
            );
            assertStatus(
                400,
                fixture.send(
                    "POST",
                    CONTEXT,
                    workspace.toCanonicalJson(),
                    "text/plain"
                )
            );
            assertStatus(
                400,
                fixture.send("GET", CONTEXT + "/run-1", "", null)
            );
            HttpResponse<String> missing = fixture.send(
                "GET",
                CONTEXT + "/" + digest(sha("missing")),
                "",
                null
            );
            assertStatus(404, missing);
            assertTrue(missing.body().contains("RUN_NOT_FOUND"));
            assertStatus(
                400,
                fixture.send("GET", CONTEXT + "?limit=501", "", null)
            );
            HttpResponse<String> method = fixture.send(
                "DELETE",
                CONTEXT,
                "",
                null
            );
            assertStatus(405, method);
            assertEquals(
                "GET, POST",
                method.headers().firstValue("Allow").orElseThrow()
            );
        }

        WebSecurityConfig bounded = WebSecurityConfig.builder()
            .maxRequestBytes(1024)
            .build();
        try (Fixture fixture = new Fixture(
                directory.resolve("bounded"),
                bounded)) {
            String oversizedBody = workspace.toCanonicalJson().repeat(2);
            assertTrue(
                oversizedBody.getBytes(StandardCharsets.UTF_8).length > 1024
            );
            HttpResponse<String> oversized = fixture.send(
                "POST",
                CONTEXT,
                oversizedBody,
                "application/json"
            );
            assertStatus(413, oversized);
            assertTrue(oversized.body().contains("PAYLOAD_TOO_LARGE"));
            assertTrue(oversized.body().contains("\"limitBytes\":1024"));
        }
    }

    private static void assertLoaded(
        Fixture fixture,
        RepresentationDiscoveryRunWorkspace workspace
    ) throws Exception {
        HttpResponse<String> loaded = fixture.send(
            "GET",
            CONTEXT + "/" + digest(workspace.runId()),
            "",
            null
        );
        assertEquals(200, loaded.statusCode());
        assertEquals(workspace.toCanonicalJson(), loaded.body());
        assertEquals(
            "\"" + digest(workspace.runId()) + "\"",
            loaded.headers().firstValue("ETag").orElseThrow()
        );
    }

    private static void assertStatus(
        int expected,
        HttpResponse<String> response
    ) {
        assertEquals(expected, response.statusCode(), response.body());
    }

    private static RepresentationDiscoveryRunWorkspace workspace(long seed) {
        return RepresentationDiscoveryRunWorkspace.create(
            RepresentationDiscoveryRunInput.expression(
                "sin(x)^2 + (cos(x)^2 + 0)",
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
                seed,
                List.of("internal:java25", "sympy:1.14.0")
            ),
            RepresentationDiscoveryRunOutcome.create(
                TerminalState.COMPLETED,
                "CANDIDATES_RETAINED",
                100,
                80,
                sha("work-ledger"),
                sha("runtime-diagnostics")
            ),
            completeArtifacts(),
            RepresentationDiscoveryRevisionEvidence.create(
                REPOSITORY_COMMIT,
                "Regelsuche-workbench/0.3-SNAPSHOT"
            )
        );
    }

    private static List<RepresentationDiscoveryArtifactReference>
            completeArtifacts() {
        List<RepresentationDiscoveryArtifactReference> references =
            new ArrayList<>(
                RepresentationDiscoveryRunWorkspace.notProducedArtifacts());
        replace(
            references,
            RepresentationDiscoveryArtifactReference.available(
                ArtifactRole.SEARCH_GRAPH,
                "regelsuche.search-graph/v1",
                sha("search-graph")
            )
        );
        replace(
            references,
            RepresentationDiscoveryArtifactReference.available(
                ArtifactRole.REPRESENTATION_CANDIDATES,
                "regelsuche.representation-candidates/v1",
                sha("candidates")
            )
        );
        replace(
            references,
            RepresentationDiscoveryArtifactReference.unavailable(
                ArtifactRole.RULE_RADAR,
                ArtifactStatus.UNSUPPORTED,
                "NOT_AVAILABLE_FOR_RETAINED_RUN"
            )
        );
        return references;
    }

    private static void replace(
        List<RepresentationDiscoveryArtifactReference> references,
        RepresentationDiscoveryArtifactReference replacement
    ) {
        references.removeIf(reference ->
            reference.role() == replacement.role());
        references.add(replacement);
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    private static String sha(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String digest(String runId) {
        return runId.substring("sha256:".length());
    }

    private static final class Fixture implements AutoCloseable {
        private final WebWorkbenchServer server;
        private final HttpClient client;
        private final URI baseUri;

        private Fixture(
            Path directory,
            WebSecurityConfig securityConfig
        ) throws IOException {
            System.setProperty(
                "regelsuche.discovery.runs.directory",
                directory.toString()
            );
            server = new WebWorkbenchServer(
                "127.0.0.1",
                0,
                new InMemoryExpressionGraphStore(),
                new InMemoryRuleInventoryRepository(),
                new DefaultTransformationExportService(),
                securityConfig
            );
            server.start();
            client = HttpClient.newHttpClient();
            baseUri = URI.create(
                "http://127.0.0.1:" + server.boundPort());
        }

        private HttpResponse<String> send(
            String method,
            String path,
            String body,
            String contentType
        ) throws IOException, InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                baseUri.resolve(path));
            if (contentType != null) {
                request.header("Content-Type", contentType);
            }
            HttpRequest.BodyPublisher publisher = body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(
                    body,
                    StandardCharsets.UTF_8
                );
            return client.send(
                request.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
        }

        @Override
        public void close() {
            try {
                server.stop();
            } finally {
                System.clearProperty(
                    "regelsuche.discovery.runs.directory");
            }
        }
    }
}
