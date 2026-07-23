package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenApiRouteRegistryTest {

    @Test
    void loadsEveryCanonicalOperationAndContext() {
        OpenApiRouteRegistry registry = OpenApiRouteRegistry.load();

        assertEquals(55, registry.routes().size());
        assertEquals(22, registry.contexts().size());
        assertTrue(registry.contexts().containsAll(Set.of(
            "/api/search",
            "/api/paths",
            "/api/proof/jobs",
            "/api/didactic",
            "/api/rule-radar"
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
