package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.domain.*;
import de.regelsuche.discovery.domain.DiscoveryDomain.*;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DomainModelProofHttpTest {
    private static final String BASE = "/api/discovery-domains/exports";
    @TempDir Path temporary;
    private final JsonMapper json = new JsonMapper();
    private WebWorkbenchServer server;
    private HttpClient client;
    private String previous;

    @BeforeEach void start() throws Exception {
        previous = System.getProperty("regelsuche.discovery.runs.directory");
        System.setProperty("regelsuche.discovery.runs.directory", temporary.resolve("runs").toString());
        server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), WebSecurityConfig.none());
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach void stop() {
        if (client != null) client.close();
        if (server != null) server.stop();
        if (previous == null) System.clearProperty("regelsuche.discovery.runs.directory");
        else System.setProperty("regelsuche.discovery.runs.directory", previous);
    }

    @Test void provesBothRetainedModelsAndReloadsExactReceiptsWithoutChangingSources() throws Exception {
        for (boolean recurrence : new boolean[]{false, true}) {
            var view = importSource(recurrence, recurrence
                ? "observed=2,3,5,8,13,21;holdout=34,55,89;maximumOrder=3"
                : "observed=1,4,9,16;holdout=25,36");
            String path = path(view);
            var before = originalFiles(path);
            var result = request("POST", path + "/model-proof", bound(view));
            assertEquals(200, result.statusCode(), result.body());
            var proof = json.readTree(result.body());
            assertEquals("regelsuche.domain-model-proof/v1", proof.path("schema").asText());
            assertEquals("PROVED_MODEL", proof.path("status").asText(), result.body());
            assertEquals("NOT_ESTABLISHED", proof.path("originalSequenceProofStatus").asText());
            assertEquals("NOT_EVALUATED", proof.path("promotionStatus").asText());
            assertEquals("NOT_EVALUATED", proof.path("publicEvidenceStatus").asText());
            assertEquals(view.path("contentHash"), proof.at("/source/workspaceHash"));
            assertEquals(view.at("/evidence/selectedCandidateHash"), proof.at("/source/selectedCandidateHash"));
            var prediction = java.util.stream.StreamSupport.stream(proof.at("/proof/queries").spliterator(), false)
                .filter(row -> row.path("index").asInt() == 100).findFirst().orElseThrow();
            assertEquals("MODEL_PREDICTION", prediction.path("role").asText());
            assertTrue(prediction.path("matchesRetained").isNull());
            if (!recurrence) assertEquals("10201", prediction.path("value").asText());
            String location = result.headers().firstValue("Location").orElseThrow();
            assertEquals(path + "/model-proof/" + proof.path("contentHash").asText().substring(7), location);
            assertEquals(view.path("runId").asText(), result.headers().firstValue("X-Regelsuche-Run-Id").orElseThrow());
            var reloaded = request("GET", location, null);
            assertEquals(200, reloaded.statusCode(), reloaded.body());
            assertEquals(result.body(), reloaded.body());
            assertEquals(result.headers().firstValue("ETag"), reloaded.headers().firstValue("ETag"));
            assertEquals(result.body(), request("POST", path + "/model-proof", bound(view)).body());
            assertEquals(before, originalFiles(path));
            assertEquals(view, json.readTree(request("GET", path, null).body()));
            Path proofDirectory = proofDirectory(view);
            try (var receipts = Files.list(proofDirectory)) { assertEquals(1, receipts.count()); }
        }
    }

    @Test void rejectsForeignBindingsMalformedBodiesAndWrongMethodsBeforeExecuting() throws Exception {
        var view = importSource(false, "observed=1,4,9,16;holdout=25,36");
        String path = path(view) + "/model-proof";
        var foreign = json.readTree(bound(view));
        ((ObjectNode) foreign).put("expectedEvidenceHash", "sha256:" + "f".repeat(64));
        var mismatch = request("POST", path, json.writeValueAsString(foreign));
        assertEquals(409, mismatch.statusCode(), mismatch.body());
        assertEquals("SOURCE_BINDING_MISMATCH", json.readTree(mismatch.body()).path("code").asText());
        for (String body : List.of("{", "{}", "{\"expectedWorkspaceHash\":null,\"expectedEvidenceHash\":null}",
                "{\"expectedWorkspaceHash\":\"x\",\"expectedWorkspaceHash\":\"y\",\"expectedEvidenceHash\":\"z\"}",
                bound(view).replace("}", ",\"approve\":true}"))) {
            var invalid = request("POST", path, body);
            assertEquals(400, invalid.statusCode(), invalid.body());
            assertTrue(invalid.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        }
        assertEquals(405, request("GET", path, null).statusCode());
        assertEquals(405, request("PUT", path, bound(view)).statusCode());
        assertEquals(405, request("POST", path + "/" + "f".repeat(64), bound(view)).statusCode());
        var plain = client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(bound(view))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, plain.statusCode(), plain.body());
        assertFalse(Files.exists(proofDirectory(view)));
    }

    @Test void refusesOversizedBodiesBeforeLookingUpUnknownSources() throws Exception {
        for (String suffix : List.of("/" + "f".repeat(64), path(importSource(false,
                "observed=1,4,9,16;holdout=25,36")).substring(BASE.length()))) {
            var result = request("POST", BASE + suffix + "/model-proof", " ".repeat(4097));
            assertEquals(413, result.statusCode(), result.body());
        }
    }

    @Test void rehashedFalseProofAndForeignReceiptCannotBeDownloadedAsVerified() throws Exception {
        var first = importSource(false, "observed=1,4,9,16;holdout=25,36");
        var second = importSource(false, "observed=2,8,18,32;holdout=50,72");
        var response = request("POST", path(first) + "/model-proof", bound(first));
        assertEquals(200, response.statusCode(), response.body());
        var proof = (ObjectNode) json.readTree(response.body());
        var falseProof = proof.deepCopy();
        ((ObjectNode) falseProof.path("proof").path("queries").get(0)).put("value", "999");
        rehash((ObjectNode) falseProof.path("proof"));
        String forgedHash = rehash(falseProof);
        Files.writeString(proofDirectory(first).resolve(forgedHash.substring(7) + ".json"), json.writeValueAsString(falseProof));
        var corrupt = request("GET", path(first) + "/model-proof/" + forgedHash.substring(7), null);
        assertEquals(500, corrupt.statusCode(), corrupt.body());
        Files.createDirectories(proofDirectory(second));
        String originalHash = proof.path("contentHash").asText();
        Files.writeString(proofDirectory(second).resolve(originalHash.substring(7) + ".json"), response.body());
        assertEquals(500, request("GET", path(second) + "/model-proof/" + originalHash.substring(7), null).statusCode());
        assertEquals(200, request("GET", response.headers().firstValue("Location").orElseThrow(), null).statusCode());
        assertEquals(404, request("GET", path(first) + "/model-proof/" + "e".repeat(64), null).statusCode());
        assertEquals(400, request("GET", path(first) + "/model-proof/invalid", null).statusCode());
    }

    @Test void retainsUnsupportedAndNoCandidateOutcomesWithoutInventingProofs() throws Exception {
        for (String payload : List.of("observed=0,1,16,81,256,625;holdout=1296,2401",
                "observed=1,4,9,16;holdout=26")) {
            var view = importSource(false, payload);
            var response = request("POST", path(view) + "/model-proof", bound(view));
            assertEquals(200, response.statusCode(), response.body());
            var receipt = json.readTree(response.body());
            assertNotEquals("PROVED_MODEL", receipt.path("status").asText(), response.body());
            assertTrue(receipt.path("proof").isNull());
            assertEquals(response.body(), request("GET", response.headers().firstValue("Location").orElseThrow(), null).body());
        }
    }

    @Test void reportsSymlinkedProofRunAsServerCorruptionForValidGetAndPost() throws Exception {
        var view = importSource(false, "observed=1,4,9,16;holdout=25,36");
        var response = request("POST", path(view) + "/model-proof", bound(view));
        assertEquals(200, response.statusCode(), response.body());
        Path proofRun = proofDirectory(view);
        Path original = temporary.resolve("saved-proof-run");
        Files.move(proofRun, original);
        Files.createSymbolicLink(proofRun, original);
        assertEquals(500, request("GET", response.headers().firstValue("Location").orElseThrow(), null).statusCode());
        assertEquals(500, request("POST", path(view) + "/model-proof", bound(view)).statusCode());
    }

    @Test void reportsSymlinkedProofRootAsServerCorruptionForValidGetAndPost() throws Exception {
        var view = importSource(false, "observed=1,4,9,16;holdout=25,36");
        var response = request("POST", path(view) + "/model-proof", bound(view));
        assertEquals(200, response.statusCode(), response.body());
        Path root = proofDirectory(view).getParent();
        Path original = temporary.resolve("saved-proof-root");
        Files.move(root, original);
        Files.createSymbolicLink(root, original);
        assertEquals(500, request("GET", response.headers().firstValue("Location").orElseThrow(), null).statusCode());
        assertEquals(500, request("POST", path(view) + "/model-proof", bound(view)).statusCode());
    }

    @Test void reportsDamagedRetainedSourceAsServerCorruptionForValidProofRequests() throws Exception {
        var view = importSource(false, "observed=1,4,9,16;holdout=25,36");
        var response = request("POST", path(view) + "/model-proof", bound(view));
        assertEquals(200, response.statusCode(), response.body());
        Path source = temporary.resolve("runs-domain-exports").resolve(view.path("runId").asText().substring(7))
            .resolve("export/evidence.json");
        Files.writeString(source, "{}");
        assertEquals(500, request("GET", response.headers().firstValue("Location").orElseThrow(), null).statusCode());
        assertEquals(500, request("POST", path(view) + "/model-proof", bound(view)).statusCode());
    }

    private JsonNode importSource(boolean recurrence, String payload) throws Exception {
        DiscoveryDomain<?, ?, ?> domain = recurrence ? new LinearRecurrenceSequenceDomain() : new FiniteDifferenceSequenceDomain();
        var source = new DomainDiscoveryRunner().run("public-model-http", domain,
            DiscoverySeed.create("source", domain.domainId(), payload, "ordinary model proof control"),
            new DiscoveryBudget(6, 32, 64, 16, 16, 128)).evidence();
        Path sourceDirectory = Files.createTempDirectory(temporary, "source-");
        new DomainDiscoveryExport().write(sourceDirectory, source);
        Map<String, String> files = new TreeMap<>();
        for (String name : List.of("domain.json", "evidence.json", "lifecycle-handoff.json", "export-manifest.json")) {
            files.put(name, Base64.getEncoder().encodeToString(Files.readAllBytes(sourceDirectory.resolve(name))));
        }
        var response = request("POST", BASE, json.writeValueAsString(Map.of("schema", DomainExportWorkspaceRepository.UPLOAD_SCHEMA, "files", files)));
        assertEquals(201, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private Map<String, String> originalFiles(String path) throws Exception {
        Map<String, String> result = new TreeMap<>();
        for (String name : List.of("domain.json", "evidence.json", "lifecycle-handoff.json", "export-manifest.json")) {
            var response = request("GET", path + "/files/" + name, null);
            assertEquals(200, response.statusCode(), response.body());
            result.put(name, response.body());
        }
        return result;
    }

    private String rehash(ObjectNode value) throws Exception {
        value.remove("contentHash");
        String hash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(json.writeValueAsBytes(value)));
        value.put("contentHash", hash);
        return hash;
    }

    private String path(JsonNode view) { return BASE + "/" + view.path("runId").asText().substring(7); }
    private Path proofDirectory(JsonNode view) { return temporary.resolve("runs-domain-exports-model-proofs").resolve(view.path("runId").asText().substring(7)); }
    private String bound(JsonNode view) throws Exception {
        return json.writeValueAsString(Map.of("expectedWorkspaceHash", view.path("contentHash").asText(),
            "expectedEvidenceHash", view.at("/evidence/contentHash").asText()));
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + server.boundPort() + path); }
    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
