package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.plugin.PluginRuntimeConfig;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.proof.ProofBridge;
import de.regelsuche.proof.ProofBridgeService;
import de.regelsuche.proof.ProverExecutor;
import de.regelsuche.search.memory.InMemoryTranspositionTable;
import de.regelsuche.search.memory.SearchMemory;
import de.regelsuche.search.memory.TranspositionEntry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebWorkbenchServerTest {

    private WebWorkbenchServer server;

    @BeforeEach
    void start() throws IOException {
        server = new WebWorkbenchServer(
            "127.0.0.1",
            0,
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService()
        );
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void servesIndexHtml() throws IOException {
        HttpURLConnection connection = open("/");
        assertEquals(200, connection.getResponseCode());
        String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("Regelsuche"));
    }

    @Test
    void emptyPathsReturnsValidJson() throws IOException {
        HttpURLConnection connection = open("/api/paths");
        assertEquals(200, connection.getResponseCode());
        String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("{\"transformations\":[]}", body);
    }

    @Test
    void inventoryGetReturnsRulesArray() throws IOException {
        HttpURLConnection connection = open("/api/inventory");
        assertEquals(200, connection.getResponseCode());
        String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("{\"rules\":[]}", body);
    }

    @Test
    void pluginsApiExposesProfileFilteredRuleStatus(@TempDir Path tempDir) throws IOException {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("profiles.regelsuche"), """
            rule keep_rule:
              pattern: A + 0
              replace: A
              tags:
                - algebra

            rule blocked_rule:
              pattern: A * 1
              replace: A
              tags:
                - algebra

            profile school_algebra:
              enable_tags:
                - algebra
              blacklist:
                - blocked_rule
            """);

        server.stop();
        server = new WebWorkbenchServer(
            "127.0.0.1",
            0,
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            WebSecurityConfig.none(),
            new PluginRuntimeConfig(tempDir.resolve("plugins"), rulesDir, false, Set.of(), Set.of())
        );
        server.start();

        HttpURLConnection connection = open("/api/plugins/rules?profile=school_algebra");
        assertEquals(200, connection.getResponseCode());
        String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(body.contains("\"activeProfile\":\"school_algebra\""), body);
        assertTrue(body.contains("\"id\":\"keep_rule\""), body);
        assertTrue(body.contains("\"id\":\"blocked_rule\""), body);
        assertTrue(body.contains("\"enabled\":false"), body);
    }

    @Test
    void pluginsApiExposesPluginCatalogMetadata() throws IOException {
        HttpURLConnection connection = open("/api/plugins");
        assertEquals(200, connection.getResponseCode());
        String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(body.contains("\"id\":\"binomial-formulas\""), body);
        assertTrue(body.contains("\"apiVersion\":\"1\""), body);
        assertTrue(body.contains("\"minimumCoreVersion\":\"1.0.0\""), body);
        assertTrue(body.contains("\"compatibility\":\"not-checked\""), body);
        assertTrue(body.contains("\"dependencies\""), body);
        assertTrue(body.contains("\"status\":\"version-not-checked\""), body);
        assertTrue(body.contains("\"provenance\":\"https://github.com/carstenartur/Regelsuche"), body);
        assertTrue(body.contains("\"signaturePresent\":false"), body);
        assertTrue(body.contains("\"signatureVerified\":false"), body);
        assertTrue(body.contains("\"trustedSource\":true"), body);
        assertTrue(body.contains("\"trustWarnings\":[]"), body);
    }

    @Test
    void mermaidExportReturnsText() throws IOException {
        HttpURLConnection connection = open("/api/exports/mermaid");
        assertEquals(200, connection.getResponseCode());
        String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.startsWith("graph TD"));
    }

    @Test
    void searchRequiresExpression() throws IOException {
        HttpURLConnection connection = open("/api/search");
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream stream = connection.getOutputStream()) {
            stream.write("{\"expression\":\"\"}".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(400, connection.getResponseCode());
    }

    @Test
    void memoryEndpointUsesInjectedSearchMemory() throws IOException {
        server.stop();
        SearchMemory memory = new SearchMemory(new InMemoryTranspositionTable());
        Instant now = Instant.now();
        memory.table().record(new TranspositionEntry(
            "hash-1",
            "x + 0",
            3,
            1,
            "path-1",
            Set.of("rule-1"),
            1,
            now,
            now
        ));
        server = new WebWorkbenchServer(
            "127.0.0.1",
            0,
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            WebSecurityConfig.none(),
            memory
        );
        server.start();
        HttpURLConnection connection = open("/api/memory");
        assertEquals(200, connection.getResponseCode());
        String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"size\":1"), body);
    }

    @Test
    void injectedProofBridgeServiceIsUsedByDemoAndProofBridgeEndpoints() throws IOException {
        server.stop();
        ProofBridge provingBridge = (left, right, assumptions) ->
            new ProofBridge.ProofAttempt(CandidateProofStatus.FORMALLY_PROVABLE, "theorem demo", "lean4");
        ProverExecutor successExecutor = new ProverExecutor(List.of("cat"), "lean4", ".lean");
        ProofBridgeService provingService = new ProofBridgeService(provingBridge, null, successExecutor);
        server = new WebWorkbenchServer(
            "127.0.0.1",
            0,
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            WebSecurityConfig.none(),
            new SearchMemory(),
            provingService,
            provingService
        );
        server.start();

        HttpURLConnection demo = open("/api/demo/math-equation");
        demo.setRequestMethod("POST");
        demo.setDoOutput(true);
        String demoBody = new String(demo.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(demoBody.contains("\"proofOutcome\""), demoBody);
        assertTrue(demoBody.contains("\"proverStatus\":\"PROVER_CONFIRMED\""), demoBody);
        assertTrue(demoBody.contains("\"proofStatus\":\"FORMALLY_PROVED\""), demoBody);

        HttpURLConnection proof = open("/api/proof-bridge");
        proof.setRequestMethod("POST");
        proof.setDoOutput(true);
        proof.setRequestProperty("Content-Type", "application/json");
        try (OutputStream stream = proof.getOutputStream()) {
            stream.write("{\"leftPattern\":\"x + 3 = 7\",\"rightPattern\":\"x = 4\",\"tool\":\"lean4\"}"
                .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, proof.getResponseCode());
        String proofBody = new String(proof.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(proofBody.contains("\"proverStatus\":\"PROVER_CONFIRMED\""), proofBody);
        assertTrue(proofBody.contains("\"proofStatus\":\"FORMALLY_PROVED\""), proofBody);
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
