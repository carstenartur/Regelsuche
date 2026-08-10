package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.json.JsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenApiStaticReferenceTest {
    private static final Set<String> HTTP_METHODS =
        Set.of("get", "post", "put", "patch", "delete", "options", "head");

    private WebWorkbenchServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new WebWorkbenchServer(
            "127.0.0.1",
            0,
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void workbenchHelpLinksToTheCanonicalOpenApiReference() throws IOException {
        StaticResource workbench = get("/");
        assertEquals(200, workbench.status());
        assertTrue(workbench.contentType().startsWith("text/html"));
        assertTrue(workbench.body().contains("Technische REST-Referenz"));
        assertTrue(workbench.body().contains("href=\"/static/openapi/index.html\""));
        assertTrue(workbench.body().contains("href=\"/static/openapi/openapi.json\""));
    }

    @Test
    void enforcesDocumentedMethodsAndConcretePathTemplatesBeforeDispatch() throws IOException {
        StaticResource pathsWithWrongMethod = request("POST", "/api/paths");
        assertEquals(405, pathsWithWrongMethod.status());
        assertTrue(pathsWithWrongMethod.allow().contains("GET"));

        StaticResource searchWithWrongMethod = request("GET", "/api/search");
        assertEquals(405, searchWithWrongMethod.status());
        assertTrue(searchWithWrongMethod.allow().contains("POST"));

        StaticResource undocumentedSubpath = get("/api/paths/example/undocumented");
        assertEquals(404, undocumentedSubpath.status());
        assertTrue(undocumentedSubpath.body().contains("API route not found"));

        StaticResource canonicalInspectRoute = get("/api/inspect/tree");
        assertEquals(400, canonicalInspectRoute.status());
        assertTrue(canonicalInspectRoute.body().contains("expression query parameter is required"));

        StaticResource inspectWithWrongMethod = request("POST", "/api/inspect/tree");
        assertEquals(405, inspectWithWrongMethod.status());
        assertTrue(inspectWithWrongMethod.allow().contains("GET"));

        StaticResource obsoleteInspectRoute = request("POST", "/api/inspect");
        assertEquals(404, obsoleteInspectRoute.status());
        assertTrue(obsoleteInspectRoute.body().contains("API route not found"));
    }

    @Test
    void servesOfflineOpenApiReferenceAndPinnedOfficialSwaggerUiAssets() throws IOException {
        StaticResource page = get("/static/openapi/index.html");
        assertEquals(200, page.status());
        assertTrue(page.contentType().startsWith("text/html"));
        assertTrue(page.body().contains("Swagger / OpenAPI-Referenz"));
        assertTrue(page.body().contains("vendor/swagger-ui-bundle.js"));
        assertTrue(page.body().contains("vendor/swagger-ui-standalone-preset.js"));
        assertTrue(page.body().contains("vendor/swagger-ui.css"));
        assertTrue(page.body().contains("href=\"vendor/LICENSE\""));
        assertTrue(page.body().contains("href=\"vendor/NOTICE\""));
        assertFalse(page.body().contains("https://"), "the reference page must not load CDN assets");
        assertFalse(page.body().contains("http://"), "the reference page must not load external assets");

        StaticResource initializer = get("/static/openapi/swagger-ui.js");
        assertEquals(200, initializer.status());
        assertTrue(initializer.contentType().startsWith("application/javascript"));
        assertTrue(initializer.body().contains("SwaggerUIBundle"));
        assertTrue(initializer.body().contains("url: 'openapi.json'"));
        assertTrue(initializer.body().contains("validatorUrl: null"));

        StaticResource shellStylesheet = get("/static/openapi/swagger-ui.css");
        assertEquals(200, shellStylesheet.status());
        assertTrue(shellStylesheet.contentType().startsWith("text/css"));
        assertTrue(shellStylesheet.body().contains(".swagger-ui .topbar"));

        StaticResource officialBundle = get("/static/openapi/vendor/swagger-ui-bundle.js");
        assertEquals(200, officialBundle.status());
        assertTrue(officialBundle.contentType().startsWith("application/javascript"));
        assertTrue(officialBundle.body().contains("SwaggerUIBundle"));

        StaticResource officialPreset = get("/static/openapi/vendor/swagger-ui-standalone-preset.js");
        assertEquals(200, officialPreset.status());
        assertTrue(officialPreset.contentType().startsWith("application/javascript"));
        assertTrue(officialPreset.body().contains("SwaggerUIStandalonePreset"));

        StaticResource officialStylesheet = get("/static/openapi/vendor/swagger-ui.css");
        assertEquals(200, officialStylesheet.status());
        assertTrue(officialStylesheet.contentType().startsWith("text/css"));
        assertTrue(officialStylesheet.body().contains(".swagger-ui"));

        StaticResource license = get("/static/openapi/vendor/LICENSE");
        assertEquals(200, license.status());
        assertTrue(license.body().contains("Apache License"));
        assertTrue(license.body().contains("Version 2.0, January 2004"));

        StaticResource notice = get("/static/openapi/vendor/NOTICE");
        assertEquals(200, notice.status());
        assertTrue(notice.body().contains("swagger-ui"));
        assertTrue(notice.body().contains("SmartBear Software Inc."));

        StaticResource specification = get("/static/openapi/openapi.json");
        assertEquals(200, specification.status());
        assertTrue(specification.contentType().startsWith("application/json"));
        Map<String, Object> document = new JsonReader(specification.body()).readObject();
        assertEquals("3.1.0", document.get("openapi"));
    }

    @Test
    void specificationHasStableOperationIdsTagsResponsesAndExplicitContexts() throws IOException {
        Map<String, Object> paths = object(specification().get("paths"));
        Set<String> operationIds = new LinkedHashSet<>();
        Set<String> usedTags = new LinkedHashSet<>();
        Set<String> documentedContexts = new LinkedHashSet<>();
        int operationCount = 0;
        int requestBodyOperationCount = 0;

        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            Map<String, Object> pathItem = object(pathEntry.getValue());
            for (Map.Entry<String, Object> methodEntry : pathItem.entrySet()) {
                if (!HTTP_METHODS.contains(methodEntry.getKey())) {
                    continue;
                }
                operationCount++;
                Map<String, Object> operation = object(methodEntry.getValue());
                String operationId = String.valueOf(operation.getOrDefault("operationId", ""));
                assertFalse(operationId.isBlank(), () -> "missing operationId for "
                    + methodEntry.getKey() + " " + pathEntry.getKey());
                assertTrue(operationIds.add(operationId), () -> "duplicate operationId: " + operationId);
                assertFalse(String.valueOf(operation.getOrDefault("summary", "")).isBlank(),
                    () -> "missing summary for " + operationId);
                List<Object> tags = list(operation.get("tags"));
                assertFalse(tags.isEmpty(), () -> "missing tag for " + operationId);
                tags.forEach(tag -> usedTags.add(String.valueOf(tag)));
                Map<String, Object> responses = object(operation.get("responses"));
                assertFalse(responses.isEmpty(), () -> "missing responses for " + operationId);
                if (!object(operation.get("requestBody")).isEmpty()) {
                    requestBodyOperationCount++;
                    assertTrue(responses.containsKey("413"),
                        () -> "missing documented 413 response for " + operationId);
                }

                String context = String.valueOf(operation.getOrDefault("x-regelsuche-context", ""));
                assertFalse(context.isBlank(), () -> "missing x-regelsuche-context for " + operationId);
                assertTrue(pathEntry.getKey().equals(context) || pathEntry.getKey().startsWith(context + "/"),
                    () -> "operation " + operationId + " is outside declared context " + context);
                documentedContexts.add(context);
            }
        }

        assertTrue(operationCount >= 45, "the first public contract must cover the complete workbench surface");
        assertEquals(11, requestBodyOperationCount,
            "all documented JSON request-body operations must retain the common 413 contract");
        assertTrue(usedTags.containsAll(Set.of(
            "Search", "Paths", "Search Graph", "Proof Jobs", "Didactics", "Rule Radar")));
        assertEquals(OpenApiRouteRegistry.load().contexts(), documentedContexts,
            "OpenAPI context extensions must exactly match the executable route registry");
    }

    @Test
    void everyOpenApiPathBelongsToItsExplicitRegisteredContext() throws IOException {
        Set<String> registeredContexts = OpenApiRouteRegistry.load().contexts();
        Map<String, Object> paths = object(specification().get("paths"));
        assertFalse(registeredContexts.isEmpty());
        assertFalse(paths.isEmpty());

        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            for (Map.Entry<String, Object> methodEntry : object(pathEntry.getValue()).entrySet()) {
                if (!HTTP_METHODS.contains(methodEntry.getKey())) {
                    continue;
                }
                Map<String, Object> operation = object(methodEntry.getValue());
                String context = String.valueOf(operation.get("x-regelsuche-context"));
                assertTrue(registeredContexts.contains(context),
                    () -> "OpenAPI operation uses an unregistered context: " + context);
                assertTrue(pathEntry.getKey().equals(context) || pathEntry.getKey().startsWith(context + "/"),
                    () -> pathEntry.getKey() + " does not belong to " + context);
            }
        }
    }

    private Map<String, Object> specification() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/web/openapi/openapi.json")) {
            assertNotNull(stream, "packaged OpenAPI specification must exist");
            return new JsonReader(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).readObject();
        }
    }

    private StaticResource get(String path) throws IOException {
        return request("GET", path);
    }

    private StaticResource request(String method, String path) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.boundPort() + path);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod(method);
        int status = connection.getResponseCode();
        String contentType = connection.getHeaderField("Content-Type");
        String allow = connection.getHeaderField("Allow");
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body;
        if (stream == null) {
            body = "";
        } else {
            try (stream) {
                body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new StaticResource(
            status,
            contentType == null ? "" : contentType,
            body,
            allow == null ? "" : allow
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> values ? (List<Object>) values : List.of();
    }

    private record StaticResource(int status, String contentType, String body, String allow) {
    }
}
