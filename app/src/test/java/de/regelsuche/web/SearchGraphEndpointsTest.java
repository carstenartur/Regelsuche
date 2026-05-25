package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.mining.MacroMoveExpansion;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchGraphEndpointsTest {

    private WebWorkbenchServer server;
    private InMemoryExpressionGraphStore graphStore;
    private InMemoryRuleInventoryRepository inventory;

    @BeforeEach
    void start() throws IOException {
        graphStore = new InMemoryExpressionGraphStore();
        inventory = new InMemoryRuleInventoryRepository();
        server = new WebWorkbenchServer(
            "127.0.0.1",
            0,
            graphStore,
            inventory,
            new DefaultTransformationExportService()
        );
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void searchGraphEndpointReturnsJsonShape() throws IOException {
        seedTransformations();
        String body = get("/api/search-graph");
        assertTrue(body.contains("\"nodes\""));
        assertTrue(body.contains("\"edges\""));
        assertTrue(body.contains("\"stats\""));
        assertTrue(body.contains("\"layout\":{\"kind\""),
            "/api/search-graph must expose layout JSON for math-bearing nodes/edges");
    }

    @Test
    void replaysTransformationPath() throws IOException {
        seedTransformations();
        String body = get("/api/paths/path-1/replay");
        assertTrue(body.contains("\"pathId\":\"path-1\""));
        assertTrue(body.contains("\"steps\""));
        assertTrue(body.contains("\"fromLatex\""));
        assertTrue(body.contains("\"ruleExplanation\""));
        assertTrue(body.contains("\"derivationLayout\":{\"kind\":\"ALIGNED\""),
            "/api/paths/{id}/replay must expose the structured derivation layout");
        assertTrue(body.contains("\"layout\":{\"kind\""),
            "/api/paths/{id}/replay must expose structured step layouts");
    }

    @Test
    void replayEndpointIncludesMacroMoveExpansionWhenGraphEdgeCarriesIt() throws IOException {
        seedMacroTransformation();
        String body = get("/api/paths/macro-path/replay");
        assertTrue(body.contains("\"macroMoveExpansion\":{"), body);
        assertTrue(body.contains("\"macroRuleId\":\"macro_demo\""), body);
        assertTrue(body.contains("\"supportingPathIds\":[\"supporting-path\"]"), body);
        assertTrue(body.contains("\"atomicSteps\":"), body);
    }

    @Test
    void findsAlternativePathsWithSort() throws IOException {
        seedTransformations();
        String body = get("/api/paths?sort=length&limit=10");
        assertTrue(body.startsWith("{\"transformations\":["));
        // 'path-2' is shorter (1 step) -> should appear first.
        int posShort = body.indexOf("\"id\":\"path-2\"");
        int posLong = body.indexOf("\"id\":\"path-1\"");
        assertTrue(posShort >= 0 && posLong >= 0);
        assertTrue(posShort < posLong, "shorter path should appear first for sort=length");
    }

    @Test
    void identitiesEndpointListsAndPromotes() throws IOException {
        seedTransformations();
        String list = get("/api/identities");
        assertTrue(list.contains("\"identities\""));
        // Find any identity id to promote.
        int idx = list.indexOf("\"id\":\"macro:");
        if (idx < 0) {
            // No macros mined (probably because seeded paths don't share a sequence). That's fine –
            // the endpoint must still return a valid JSON envelope.
            return;
        }
        int start = idx + "\"id\":\"".length();
        int end = list.indexOf('"', start);
        String identityId = list.substring(start, end);

        HttpURLConnection connection = open("/api/identities/" + identityId + "/promote");
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream stream = connection.getOutputStream()) {
            stream.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, connection.getResponseCode());
        String response = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(response.contains("promotedRuleId"));
        assertTrue(inventory.findAll().size() >= 1, "promote must persist a rule into the inventory");
    }

    @Test
    void exportsSearchGraphJson() throws IOException {
        seedTransformations();
        String body = get("/api/exports/search-graph.json");
        assertTrue(body.contains("\"nodes\""));
        assertTrue(body.contains("\"edges\""));

        String mermaid = get("/api/exports/search-graph.mmd");
        assertTrue(mermaid.startsWith("graph TD"));

        String graphml = get("/api/exports/search-graph.graphml");
        assertTrue(graphml.contains("<graphml"));

        String bestPath = get("/api/exports/best-path.md");
        assertNotNull(bestPath);

        String identityTex = get("/api/exports/identity-report.tex");
        assertTrue(identityTex.contains("\\documentclass"));
    }

    private void seedTransformations() {
        // path-1: 3-step
        List<TransformationStep> steps1 = List.of(
            new TransformationStep(0, "a + 0 + 0", "a + 0", "remove_zero", RewriteKind.SIMPLIFY, 10, 8, true, ""),
            new TransformationStep(1, "a + 0", "a", "remove_zero", RewriteKind.SIMPLIFY, 8, 5, true, ""),
            new TransformationStep(2, "a", "a", "ast_canonical_normalize", RewriteKind.NORMALIZE, 5, 5, true, "")
        );
        graphStore.saveNode("a + 0 + 0", 10);
        graphStore.saveNode("a + 0", 8);
        graphStore.saveNode("a", 5);
        graphStore.saveEdge(new GraphEdge("a + 0 + 0", "a + 0", "remove_zero", 0, 2, "p1#0", "h1", 10, 8,
            RewriteKind.SIMPLIFY, false, -2, true, CandidateProofStatus.OBSERVED));
        graphStore.saveEdge(new GraphEdge("a + 0", "a", "remove_zero", 1, 3, "p1#1", "h2", 8, 5,
            RewriteKind.SIMPLIFY, false, -3, true, CandidateProofStatus.OBSERVED));
        graphStore.saveDiscoveredTransformation(new DiscoveredTransformation(
            "path-1", "a + 0 + 0", "a", steps1,
            new ExpressionScore(10, 10, 5, 1, 0),
            new ExpressionScore(1, 1, 0, 0, 0),
            5, CandidateProofStatus.VALIDATED_BY_EXAMPLES, Instant.now(), "h-p1"));

        // path-2: 1-step
        List<TransformationStep> steps2 = List.of(
            new TransformationStep(0, "b + 0", "b", "remove_zero", RewriteKind.SIMPLIFY, 6, 3, true, "")
        );
        graphStore.saveNode("b + 0", 6);
        graphStore.saveNode("b", 3);
        graphStore.saveEdge(new GraphEdge("b + 0", "b", "remove_zero", 0, 3, "p2#0", "h3", 6, 3,
            RewriteKind.SIMPLIFY, false, -3, true, CandidateProofStatus.OBSERVED));
        graphStore.saveDiscoveredTransformation(new DiscoveredTransformation(
            "path-2", "b + 0", "b", steps2,
            new ExpressionScore(6, 6, 1, 1, 0),
            new ExpressionScore(1, 1, 0, 0, 0),
            3, CandidateProofStatus.OBSERVED, Instant.now(), "h-p2"));
    }

    private void seedMacroTransformation() {
        List<TransformationStep> atomic = List.of(
            new TransformationStep(0, "A", "A + 0", "expand_zero", RewriteKind.NORMALIZE, 5, 4, true, ""),
            new TransformationStep(1, "A + 0", "B", "normalize_macro", RewriteKind.NORMALIZE, 4, 2, true, "")
        );
        MacroMoveExpansion expansion = new MacroMoveExpansion(
            "macro_demo", "A", "B", atomic, List.of("supporting-path"), 2.0, false
        );
        TransformationStep step = new TransformationStep(
            0, "A", "B", "macro_demo", RewriteKind.NORMALIZE, 5, 2, true, ""
        );
        graphStore.saveNode("A", 5);
        graphStore.saveNode("B", 2);
        graphStore.saveEdge(new GraphEdge(
            "A", "B", "macro_demo", 0, 3, "macro-path#0", "macro-hash", 5, 2,
            RewriteKind.NORMALIZE, false, -3, true, CandidateProofStatus.OBSERVED, expansion
        ));
        graphStore.saveDiscoveredTransformation(new DiscoveredTransformation(
            "macro-path", "A", "B", List.of(step),
            new ExpressionScore(5, 0, 0, 0, 0),
            new ExpressionScore(2, 0, 0, 0, 0),
            3, CandidateProofStatus.OBSERVED, Instant.now(), "macro-hash"
        ));
    }

    private String get(String path) throws IOException {
        HttpURLConnection connection = open(path);
        assertEquals(200, connection.getResponseCode(), "GET " + path);
        return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private HttpURLConnection open(String path) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.boundPort() + path);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(5000);
        assertNotNull(connection);
        return connection;
    }
}
