package de.regelsuche.web;

import de.regelsuche.evolution.RepresentationTransferExperiment;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.json.JsonReader;
import de.regelsuche.json.JsonWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepresentationStrategyHttpTest {
    private WebWorkbenchServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    @BeforeEach void start() throws Exception {
        server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService());
        server.start();
    }
    @AfterEach void stop() { server.stop(); }

    @Test void solvesWhereDirectBudgetFailsAndReplaysAfterRestart() throws Exception {
        var source = RepresentationTransferExperiment.evaluation().stream()
            .filter(task -> task.id().equals("recurrence-0")).findFirst().orElseThrow().equations();
        String request = new JsonWriter().beginObject().property("schema", "regelsuche.linear-solve-request/v1")
            .stringArray("equations", source).property("route", "AUTO").property("maxWorkUnits", 1100).endObject().toString();
        var solved = post("/api/representations/solve", request);
        assertEquals(200, solved.statusCode(), solved.body());
        assertEquals("SOLVED", result(solved.body()).get("status"));
        assertEquals("BLOCKS", result(solved.body()).get("selected"));
        assertEquals("BUDGET_INCONCLUSIVE", result(post("/api/representations/solve", request.replace("AUTO", "DIRECT")).body()).get("status"));
        server.stop(); start();
        var replay = post("/api/representations/solve/replay", solved.body());
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(solved.body(), replay.body());
        assertEquals("VERIFIED", replay.headers().firstValue("X-Representation-Replay").orElseThrow());
        String hash = (String) new JsonReader(solved.body()).readObject().get("contentHash");
        assertEquals(409, post("/api/representations/solve/replay", solved.body().replace(hash, "0".repeat(64))).statusCode());
        assertEquals(400, post("/api/representations/solve", request.replace("1100", "2.5")).statusCode());
        assertEquals(400, post("/api/representations/solve", request.replace("{\"schema\"", "{\"unknown\":true,\"schema\"")).statusCode());
    }

    @Test void servesRealFrozenStudyAndEnforcesDocumentedMethods() throws Exception {
        var first = get("/api/representations/study");
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(first.body(), get("/api/representations/study").body());
        var study = new JsonReader(first.body()).readObject();
        assertEquals(252, ((java.util.List<?>) study.get("rows")).size());
        assertEquals(405, get("/api/representations/solve").statusCode());
        assertEquals(405, post("/api/representations/study", "{}").statusCode());
        var page = get("/static/strategy-demo.html");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("id=\"solveForm\""));
    }
    private static Map<?, ?> result(String json) {
        return (Map<?, ?>) ((Map<?, ?>) new JsonReader(json).readObject().get("evidence")).get("result");
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + server.boundPort() + path); }
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
