package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.json.JsonReader;
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
import java.util.Map;
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
    void derivesAPairReductionFromAnEditableSummand() throws IOException {
        var connection = postPairReduction("""
            {"columns":["x"],"summand":"(x_i-x_j)^2","rowCount":100000,
             "rows":[["2"],["-1"],["5"]]}
            """);
        assertEquals(200, connection.getResponseCode());
        var body = new de.regelsuche.json.JsonReader(
            new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).readObject();
        assertEquals("VERIFIED", body.get("status"));
        assertEquals("4999950000", body.get("originalPairs"));
        assertEquals("54", body.get("sampleResult"));
        assertEquals(java.util.List.of("normalize-pair-summand", "reduce-pair-sum"), body.get("checkedRules"));
        assertNotNull(body.get("formula"));
        assertFalse(body.containsKey("javaSource"), "Java source emission belongs to the Sandbox adapter");
    }

    @Test
    void hallDemoVerifiesTheLocalDeficitAgainstItsDisplayedGraph() throws IOException {
        var body = certificateDemo("hall");
        assertEquals("VERIFIED", body.get("status"));
        assertEquals(4, body.get("leftCount"));
        assertEquals(4, body.get("rightCount"));
        assertEquals(List.of(List.of(0, 0), List.of(1, 0), List.of(2, 0),
            List.of(3, 1), List.of(3, 2), List.of(3, 3)), body.get("edges"));
        assertEquals(List.of(List.of(0, 0), List.of(3, 1)), body.get("matching"));
        assertEquals(List.of(0, 1, 2), body.get("witness"));
        assertEquals(List.of(0), body.get("neighbors"));
        assertEquals(0, body.get("countDeficit"));
        assertEquals(2, body.get("hallDeficit"));
    }

    @Test
    void montgomeryDemoVerifiesItsWordBoundedCanonicalResiduePlan() throws IOException {
        var body = certificateDemo("montgomery");
        assertEquals("VERIFIED", body.get("status"));
        assertEquals(3329, body.get("modulus"));
        assertEquals(16, body.get("radixBits"));
        assertEquals(62209, body.get("inverse"));
        assertEquals("CANONICAL_RESIDUE", body.get("observation"));
        assertEquals(4, body.get("sourceReductions"));
        assertEquals(1, body.get("targetReductions"));
        assertEquals(List.of(-80_000, 80_000), body.get("targetAccumulatorRange"));
        assertEquals(List.of(-100, 100), body.get("leftRange"));
        assertEquals(List.of(-200, 200), body.get("rightRange"));
    }

    @Test
    void pairDemoExecutesTheCheckedPlanAndMatchesAPrefixOracle() throws IOException {
        var body = certificateDemo("pair-reduction");
        assertEquals("VERIFIED", body.get("status"));
        assertEquals(100_000, body.get("evaluatedRows"));
        assertEquals("8333333332500000000", body.get("value"));
        assertEquals("4999950000", body.get("baselinePairs"));
        assertEquals(64, body.get("prefixRows"));
        assertEquals(2016, body.get("prefixPairs"));
        assertEquals("1397760", body.get("prefixReducedValue"));
        assertEquals("1397760", body.get("prefixDirectValue"));
    }

    private Map<String, Object> certificateDemo(String demo) throws IOException {
        var connection = open("/api/certificate-demos/" + demo);
        assertEquals(200, connection.getResponseCode());
        try (var response = connection.getInputStream()) {
            return new JsonReader(new String(response.readAllBytes(), StandardCharsets.UTF_8)).readObject();
        }
    }

    @Test
    void pairReductionRejectsAnAsymmetricKernelAndInvalidSampleRows() throws IOException {
        var asymmetric = postPairReduction("""
            {"columns":["x"],"summand":"x_i-x_j","rowCount":3}
            """);
        assertEquals(200, asymmetric.getResponseCode());
        var body = new de.regelsuche.json.JsonReader(
            new String(asymmetric.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).readObject();
        assertEquals("NO_PLAN", body.get("status"));
        assertEquals("ASYMMETRIC_SUMMAND", body.get("reason"));
        assertEquals(400, postPairReduction("""
            {"columns":["x","w"],"summand":"x_i*x_j","rowCount":3,"rows":[["1"]]}
            """).getResponseCode());
    }

    @Test
    void displaysAnExactExpansionLargerThanTheInputLiteralLimit() throws IOException {
        String coefficient = "1" + "0".repeat(599);
        var response = postPairReduction("""
            {"columns":["x"],"summand":"%s^2","rowCount":2}
            """.formatted(coefficient));
        assertEquals(200, response.getResponseCode());
        var body = new de.regelsuche.json.JsonReader(
            new String(response.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).readObject();
        assertEquals("1" + "0".repeat(1198), body.get("expandedSummand"));
    }

    @Test
    void keepsTheCheckedPlanIfSampleEvaluationExhaustsItsBudget() throws IOException {
        String cell = "\"" + "9".repeat(36) + "\"";
        String row = "[" + cell + "," + cell + "," + cell + "]";
        String rows = String.join(",", java.util.Collections.nCopies(256, row));
        var response = postPairReduction("""
            {"columns":["x","y","z"],"summand":"(x_i+y_i+z_i)^8+(x_j+y_j+z_j)^8",
             "rowCount":256,"rows":[%s]}
            """.formatted(rows));
        assertEquals(200, response.getResponseCode());
        var body = new de.regelsuche.json.JsonReader(
            new String(response.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).readObject();
        assertEquals("VERIFIED", body.get("status"));
        assertEquals("WORK_LIMIT", body.get("sampleUnavailable"));
        assertTrue(!body.containsKey("sampleResult"));
    }

    @Test
    void derivesAWindowWithAnOpenLowerBoundAndKeepsEvictionRoles() throws IOException {
        var response = postPairReduction("""
            {"columns":["x","u"],"summand":"u_0*(x_0-x_1)^2","rowCount":100000,"windowWidth":3,
             "restrictions":[{"left":"x","right":"x","lower":"0","lowerInclusive":false,"upper":"2"}],
             "rows":[["4","2"],["2","1"],["0","-3"],["1","6"]]}
            """);
        assertEquals(200, response.getResponseCode());
        var body = new de.regelsuche.json.JsonReader(
            new String(response.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).readObject();
        assertEquals("VERIFIED", body.get("status"));
        assertEquals("299994", body.get("originalCandidates"));
        assertEquals(List.of("12", "5"), body.get("sampleSums"));
        assertEquals(List.of("normalize-ordered-summand", "derive-indexed-reduction"), body.get("checkedRules"));
        assertNotNull(body.get("eviction"));
    }

    @Test
    void rejectsAWindowRequestWithoutAPairOfBoundRows() throws IOException {
        assertEquals(400, postPairReduction("""
            {"columns":["x"],"summand":"1","rowCount":10,"windowWidth":3,"restrictions":[{},{}]}
            """).getResponseCode());
        assertEquals(400, postPairReduction("""
            {"columns":["x"],"summand":"1","rowCount":10,"restrictions":[{"left":"absent","right":"x","lower":"0"}]}
            """).getResponseCode());
    }

    @Test
    void countsEditedSignedCnfAndReturnsACheckedDownloadWithExactIntegers() throws IOException {
        var response = postSignedCnf("[[1,-2]]", 1_000_000);
        assertEquals(200, response.getResponseCode());
        var report = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getInputStream());
        assertEquals("EXACT", report.path("status").asText());
        assertEquals("2305843009213693952", report.path("count").textValue());
        assertEquals(62, report.at("/certificate/source/variableCount").intValue());
        assertEquals(report.path("count"), report.at("/certificate/elimination/total"));
    }

    @Test
    void rejectsZeroAsASignedCnfLiteral() throws IOException {
        assertEquals(400, postSignedCnf("[[0]]", 1_000_000).getResponseCode());
    }

    @Test
    void reportsTheCnfWorkLimitWithoutACertificate() throws IOException {
        var limited = postSignedCnf("[[1,-2]]", 1);
        assertEquals(200, limited.getResponseCode());
        var unavailable = new com.fasterxml.jackson.databind.ObjectMapper().readTree(limited.getInputStream());
        assertEquals("UNAVAILABLE", unavailable.path("status").asText());
        assertEquals("WORK_LIMIT", unavailable.path("reason").asText());
        assertFalse(unavailable.has("certificate"));
    }

    private HttpURLConnection postSignedCnf(String clauses, int maximumWork) throws IOException {
        return postJson("/api/certificate-demos/cnf/" + java.util.UUID.randomUUID(), """
            {"variableCount":62,"clauses":%s,"fixed":[1],"maximumWork":%d}
            """.formatted(clauses, maximumWork));
    }

    private HttpURLConnection postPairReduction(String request) throws IOException {
        return postJson("/api/pair-reductions", request);
    }

    private HttpURLConnection postJson(String path, String request) throws IOException {
        var connection = open(path);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        try (var body = connection.getOutputStream()) {
            body.write(request.getBytes(StandardCharsets.UTF_8));
        }
        return connection;
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
    void formulaInspectionReturnsOriginalOccurrenceRangesForBothEquationSides() throws IOException {
        var connection = open("/api/inspect/formula");
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (var stream = connection.getOutputStream()) {
            stream.write("""
                {"expression":"  f(x, x) = -(0.50)^2  ","type":"EQUATION"}
                """.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, connection.getResponseCode());
        var root = new JsonReader(new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
            .readObject();
        assertEquals("EQUATION", root.get("kind"));
        assertFalse(root.containsKey("sourceRange"), "the equation wrapper is not a term occurrence");
        var function = childOf(root, 0);
        var minus = childOf(root, 1);
        assertEquals(Map.of("startInclusive", 2, "endExclusive", 9), function.get("sourceRange"));
        assertEquals(Map.of("startInclusive", 4, "endExclusive", 5), childOf(function, 0).get("sourceRange"));
        assertEquals(Map.of("startInclusive", 7, "endExclusive", 8), childOf(function, 1).get("sourceRange"));
        assertEquals(Map.of("startInclusive", 12, "endExclusive", 21), minus.get("sourceRange"));
        assertEquals("0", childOf(minus, 0).get("value"));
        assertFalse(childOf(minus, 0).containsKey("sourceRange"), "synthetic unary zero has no source");
        var base = childOf(childOf(minus, 1), 0);
        assertEquals("1/2", base.get("value"));
        assertEquals(Map.of("startInclusive", 13, "endExclusive", 19), base.get("sourceRange"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> childOf(Map<String, Object> node, int index) {
        return ((List<Map<String, Object>>) node.get("children")).get(index);
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
        assertTrue(demoBody.contains("\"proverStatus\":\"PROCESS_SUCCEEDED\""), demoBody);
        assertTrue(demoBody.contains("\"proofStatus\":\"FORMALLY_PROVABLE\""), demoBody);

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
        assertTrue(proofBody.contains("\"proverStatus\":\"PROCESS_SUCCEEDED\""), proofBody);
        assertTrue(proofBody.contains("\"proofStatus\":\"FORMALLY_PROVABLE\""), proofBody);
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
