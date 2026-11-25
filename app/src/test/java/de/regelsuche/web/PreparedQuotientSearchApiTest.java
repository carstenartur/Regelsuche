package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.json.JsonReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PreparedQuotientSearchApiTest {
    private WebWorkbenchServer server;

    @BeforeEach
    void start() throws IOException {
        server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService());
        server.start();
    }

    @AfterEach
    void stop() { server.stop(); }

    @Test
    void selectsAndReplaysOnlyTheRequestedOccurrence() throws IOException {
        var left = search("pair(x+0,x+0)", "\"position\":{\"path\":[0],\"text\":\"x + 0\"}");
        var right = search("pair(x+0,x+0)", "\"position\":{\"path\":[1],\"text\":\"x + 0\"}");

        assertEquals("pair(x, x + 0)", left.get("bestExpression"));
        assertEquals("pair(x + 0, x)", right.get("bestExpression"));
        assertNotEquals(continuation(left).get("sourceBinding"), continuation(right).get("sourceBinding"));
        assertEquals("FIRST_PARTY_INDEXED_AST", continuation(right).get("provider"));
        assertEquals("VERIFIED_PRIMITIVE_REPLAY", continuation(right).get("replay"));
        assertTrue(((Number) object(continuation(right).get("work")).get("ruleAttempts")).longValue() > 0);
    }

    @Test
    void keepsSourceSyntaxAndAssumptionsInTheProductBinding() throws IOException {
        var first = continuation(search("x+0", "\"assumptions\":[\"x>0\"]"));
        var opposite = continuation(search("x+0", "\"assumptions\":[\"x<0\"]"));
        var differentSyntax = continuation(search("0+x", "\"assumptions\":[\"x>0\"]"));

        assertNotEquals(first.get("sourceBinding"), opposite.get("sourceBinding"));
        assertNotEquals(first.get("sourceBinding"), differentSyntax.get("sourceBinding"));
        assertNotEquals(first.get("bestRules"), differentSyntax.get("bestRules"));
    }

    @Test
    void staleAndInsufficientBudgetResultsCarryNoReplacement() throws IOException {
        var stale = search("pair(x+0,y+0)", "\"position\":{\"path\":[1],\"text\":\"x + 0\"}");
        var budget = search("x+0", "\"budget\":{\"maxVisitedExpressions\":1}");
        var complete = search("x+0", "");

        assertEquals(null, stale.get("bestExpression"));
        assertEquals("POSITION_STALE", continuation(stale).get("status"));
        assertEquals(null, budget.get("bestExpression"));
        assertEquals("STATE_BUDGET", continuation(budget).get("status"));
        assertEquals("x", complete.get("bestExpression"));
        assertNotEquals(continuation(budget).get("sourceBinding"), continuation(complete).get("sourceBinding"));
    }

    private Map<String, Object> search(String source, String continuation) throws IOException {
        var uri = URI.create("http://127.0.0.1:" + server.boundPort() + "/api/search");
        var connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        String body = "{\"expression\":\"" + source + "\",\"profile\":\"PREPARED_QUOTIENT_DISCOVERY\",\"continuation\":{" + continuation + "}}";
        try (var output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, connection.getResponseCode());
        try (var response = connection.getInputStream()) {
            return new JsonReader(new String(response.readAllBytes(), StandardCharsets.UTF_8)).readObject();
        } finally {
            connection.disconnect();
        }
    }

    private static Map<?, ?> continuation(Map<String, Object> result) {
        return object(result.get("continuation"));
    }

    private static Map<?, ?> object(Object object) { return (Map<?, ?>) object; }
}
