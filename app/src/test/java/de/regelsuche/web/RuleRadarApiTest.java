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
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuleRadarApiTest {
    private WebWorkbenchServer server;

    @BeforeEach
    void start() throws IOException {
        server = new WebWorkbenchServer(
            "127.0.0.1",
            0,
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void inspectReturnsCompletePositionAwareContract() throws IOException {
        Map<String, Object> json = post("/api/rule-radar/inspect", """
            {
              "expression":"(x + 1)^2 + 0",
              "context":{
                "includePlugins":false,
                "includeLearnedMacros":false,
                "maxCandidatesPerPosition":24,
                "maxCandidatesTotal":240
              }
            }
            """);

        assertEquals("regelsuche.ast-rule-radar/v1", json.get("schema"));
        assertEquals(Boolean.TRUE, json.get("valid"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) json.get("nodes");
        assertTrue(nodes.stream().anyMatch(node -> "root".equals(node.get("pathKey"))));
        assertTrue(nodes.stream().anyMatch(node -> "000".equals(node.get("pathKey"))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) json.get("candidates");
        Map<String, Object> addZero = candidates.stream()
            .filter(candidate -> "ast_add_zero_right".equals(candidate.get("ruleId")))
            .filter(candidate -> "root".equals(candidate.get("pathKey")))
            .findFirst().orElseThrow();
        assertFalse(String.valueOf(addZero.get("candidateId")).isBlank());
        assertEquals("CORE", addZero.get("origin"));
        assertNotNull(addZero.get("bindings"));
        assertNotNull(addZero.get("assumptions"));
        assertNotNull(addZero.get("validationStatus"));
        assertNotNull(addZero.get("expressionAfter"));
    }

    @Test
    void applyUsesAdvertisedCandidateAndReturnsRefreshedInspection() throws IOException {
        Map<String, Object> inspected = post("/api/rule-radar/inspect", """
            {"expression":"(x + 1)^2 + 0","context":{"includePlugins":false,"includeLearnedMacros":false}}
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) inspected.get("candidates");
        Map<String, Object> addZero = candidates.stream()
            .filter(candidate -> "ast_add_zero_right".equals(candidate.get("ruleId")))
            .filter(candidate -> "root".equals(candidate.get("pathKey")))
            .findFirst().orElseThrow();
        String candidateId = String.valueOf(addZero.get("candidateId"));
        String advertised = String.valueOf(addZero.get("expressionAfter"));

        Map<String, Object> applied = post("/api/rule-radar/apply", """
            {
              "expression":"(x + 1)^2 + 0",
              "candidateId":"%s",
              "context":{"includePlugins":false,"includeLearnedMacros":false}
            }
            """.formatted(candidateId));
        assertEquals(candidateId, applied.get("candidateId"));
        assertEquals(advertised, applied.get("expressionAfter"));
        @SuppressWarnings("unchecked")
        Map<String, Object> refreshed = (Map<String, Object>) applied.get("inspection");
        assertEquals(advertised, refreshed.get("expression"));
    }

    @Test
    void searchEdgeReferencesSameCandidateIdAsInspection() throws IOException {
        Map<String, Object> inspected = post("/api/rule-radar/inspect", """
            {"expression":"(x + 1)^2 + 0","context":{"includePlugins":false,"includeLearnedMacros":false}}
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) inspected.get("candidates");
        String expectedId = String.valueOf(candidates.stream()
            .filter(candidate -> "ast_add_zero_right".equals(candidate.get("ruleId")))
            .filter(candidate -> "root".equals(candidate.get("pathKey")))
            .findFirst().orElseThrow().get("candidateId"));

        Map<String, Object> searched = post("/api/rule-radar/search", """
            {
              "expression":"(x + 1)^2 + 0",
              "targetExpression":"",
              "maxDepth":1,
              "maxStates":50,
              "maxMovesPerState":50,
              "context":{"includePlugins":false,"includeLearnedMacros":false}
            }
            """);
        assertEquals(Boolean.FALSE, searched.get("targetReached"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) searched.get("edges");
        Map<String, Object> correlated = edges.stream()
            .filter(edge -> expectedId.equals(edge.get("candidateId")))
            .findFirst().orElseThrow();
        assertEquals("root", correlated.get("pathKey"));
        assertEquals(correlated.get("fromStateId"), correlated.get("toStateId"));
        assertEquals("PRUNED_KNOWN_BETTER", correlated.get("outcome"));
    }

    @Test
    void invalidExpressionIsStructuredSuccessfulResponseNotServerFailure() throws IOException {
        Map<String, Object> json = post("/api/rule-radar/inspect", """
            {"expression":"((("}
            """);
        assertEquals(Boolean.FALSE, json.get("valid"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) json.get("diagnostics");
        assertEquals("INVALID_EXPRESSION", diagnostics.getFirst().get("code"));
    }

    @Test
    void inspectReportsExplicitTruncation() throws IOException {
        Map<String, Object> json = post("/api/rule-radar/inspect", """
            {
              "expression":"(x + 1)^2 + 0",
              "context":{"includePlugins":false,"includeLearnedMacros":false,"maxCandidatesPerPosition":1,"maxCandidatesTotal":2}
            }
            """);
        @SuppressWarnings("unchecked")
        Map<String, Object> truncation = (Map<String, Object>) json.get("truncation");
        assertEquals(Boolean.TRUE, truncation.get("truncated"));
        int generated = ((Number) truncation.get("generatedCandidateCount")).intValue();
        int returned = ((Number) truncation.get("returnedCandidateCount")).intValue();
        int omitted = ((Number) truncation.get("omittedCandidateCount")).intValue();
        assertEquals(generated, returned + omitted);
    }

    private Map<String, Object> post(String path, String body) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.boundPort() + path);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        try (var output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, connection.getResponseCode(), () -> "unexpected status from " + path);
        try (var input = connection.getInputStream()) {
            return new JsonReader(new String(input.readAllBytes(), StandardCharsets.UTF_8)).readObject();
        }
    }
}
