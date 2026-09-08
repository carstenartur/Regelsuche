package de.regelsuche.web;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.json.JsonReader;
import de.regelsuche.math.algorithms.linalg.MatrixPreparation;
import de.regelsuche.math.algorithms.linalg.MatrixPreparationJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MatrixRepresentationHttpContractTest {
    private WebWorkbenchServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach void start() throws Exception {
        server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService());
        server.start();
    }

    @AfterEach void stop() { server.stop(); }

    @Test void completeArtifactReplaysOverHttpAndMatchesTheCheckout() throws Exception {
        var request = request();
        var response = post("/api/representations", MatrixPreparationJson.requestJson(request));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(MatrixPreparationJson.toJson(new MatrixPreparation().analyze(request)), response.body());
        var replay = post("/api/representations/replay", response.body());
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals("VERIFIED", replay.headers().firstValue("X-Representation-Replay").orElseThrow());
        assertEquals(response.body(), replay.body());
    }

    @Test void retainedEvidenceReplaysAfterServerRestart() throws Exception {
        String artifact = post("/api/representations", MatrixPreparationJson.requestJson(request())).body();
        server.stop(); start();
        assertEquals(200, post("/api/representations/replay", artifact).statusCode());
    }

    @Test void changedCertificateSourceMappingAndProfileAreRejected() throws Exception {
        String artifact = post("/api/representations", MatrixPreparationJson.requestJson(request())).body();
        String hash = (String) new JsonReader(artifact).readObject().get("contentHash");
        assertEquals(409, post("/api/representations/replay", artifact.replace(hash, "0".repeat(64))).statusCode());
        assertEquals(409, post("/api/representations/replay", artifact.replace("\"sourceIndex\":0", "\"sourceIndex\":1")).statusCode());
        assertEquals(409, post("/api/representations/replay", artifact.replace("SAFE_PREPARED_REPRESENTATION_V1", "RECOGNITION_ONLY_V1")).statusCode());
    }

    @Test void schemaAndDomainClaimsCannotBeSilentlyIgnored() throws Exception {
        String json = MatrixPreparationJson.requestJson(request());
        assertEquals(400, post("/api/representations", json.replace("request/v1", "request/v2")).statusCode());
        assertEquals(400, post("/api/representations", json.replace("{\"schema\"", "{\"hermitian\":true,\"schema\"")).statusCode());
        assertEquals(400, post("/api/representations", json.replace("\"maxWorkUnits\":200000", "\"maxWorkUnits\":1.5")).statusCode());
    }

    @Test void documentedSurfaceEnforcesMethodsAndServesTheRepresentationWorkbench() throws Exception {
        assertEquals(405, http.send(HttpRequest.newBuilder(uri("/api/representations")).GET().build(),
            HttpResponse.BodyHandlers.ofString()).statusCode());
        var page = http.send(HttpRequest.newBuilder(uri("/static/representations.html")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("representationForm"));
    }

    private MatrixPreparation.Request request() {
        return MatrixPreparation.Request.scalar("2*(x+y)+3*(x-y)=5; (x+y)+4*(x-y)=6", List.of("x", "y"),
            MatrixPreparation.Profile.SAFE_PREPARED_REPRESENTATION_V1, MatrixPreparation.DEFAULT_WORK);
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + server.boundPort() + path); }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
