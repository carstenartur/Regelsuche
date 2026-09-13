package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.discovery.representation.*;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RetainedCandidateDossierHttpTest {
    private static final TargetFreeRepresentationDiscoveryRun.RunBundle BUNDLE =
        TargetFreeRepresentationDiscoveryRun.run("0123456789abcdef0123456789abcdef01234567");
    @TempDir Path temporary;
    private WebWorkbenchServer server;
    private HttpClient client;
    private String previousDirectory;

    @BeforeEach void start() throws Exception {
        previousDirectory = System.getProperty("regelsuche.discovery.runs.directory");
        System.setProperty("regelsuche.discovery.runs.directory", temporary.resolve("runs").toString());
        server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), WebSecurityConfig.none());
        server.start();
        client = HttpClient.newHttpClient();
        assertEquals(201, request("POST", "", BUNDLE.workspace().toCanonicalJson()).statusCode());
    }

    @AfterEach void stop() {
        if (client != null) client.close();
        if (server != null) server.stop();
        if (previousDirectory == null) System.clearProperty("regelsuche.discovery.runs.directory");
        else System.setProperty("regelsuche.discovery.runs.directory", previousDirectory);
    }

    @Test void retainsExactBoundEvidenceAndRevalidatesItOnEveryRead() throws Exception {
        var missing = request("GET", path("dossier"), null);
        assertEquals(404, missing.statusCode());
        assertTrue(missing.body().contains("DOSSIER_NOT_RETAINED"), missing.body());
        String canonical = BUNDLE.scenario().toCanonicalJson();
        var imported = request("POST", path("dossier"), canonical + "\n");
        assertEquals(201, imported.statusCode(), imported.body());
        assertEquals(canonical, imported.body());
        assertEquals(BUNDLE.workspace().runId(), imported.headers().firstValue("X-Regelsuche-Run-Id").orElseThrow());
        assertEquals("\"" + BUNDLE.scenario().contentHash().substring(7) + "\"", imported.headers().firstValue("ETag").orElseThrow());
        assertEquals(201, request("POST", path("dossier"), canonical).statusCode());
        assertEquals(canonical, request("GET", path("dossier"), null).body());
        assertEquals(canonical, request("GET", path("dossier") + "/", null).body());
        assertEquals(BUNDLE.workspace().toCanonicalJson(), request("GET", path(""), null).body());
        assertEquals(BUNDLE.workspace().toCanonicalJson(), request("GET", path("") + "/", null).body());
        assertEquals(200, request("GET", "", null).statusCode());
        Path retained = temporary.resolve("runs-dossiers").resolve(BUNDLE.workspace().runId().substring(7) + ".json");
        Files.writeString(retained, canonical.replace("SYMBOLICALLY_VERIFIED", "FORMALLY_PROVED"));
        assertEquals(400, request("GET", path("dossier"), null).statusCode());
    }

    @Test void rejectsForeignTamperedAndUnsupportedArtifacts() throws Exception {
        String canonical = BUNDLE.scenario().toCanonicalJson();
        var tampered = request("POST", path("dossier"), canonical.replace("SYMBOLICALLY_VERIFIED", "FORMALLY_PROVED"));
        assertEquals(400, tampered.statusCode(), tampered.body());
        assertTrue(tampered.body().contains("INVALID_DOSSIER"));
        assertEquals(400, request("POST", path("dossier"), "null").statusCode());
        var plan = BUNDLE.workspace().plan();
        var changed = RepresentationDiscoveryRunPlan.create(plan.informationTrack(), plan.informationBoundaryHash(),
            plan.ruleInventoryHash(), plan.knowledgePackSelectionHash(), plan.knownStructureCatalogHash(),
            plan.searchStrategyId(), plan.searchProfileId(), plan.objectiveId(), plan.budgetHash(), 1, plan.backendIdentities());
        var duplicate = RepresentationDiscoveryRunWorkspace.duplicateWithOnePlanChange(BUNDLE.workspace(), changed, BUNDLE.workspace().revisions());
        assertEquals(201, request("POST", "", duplicate.toCanonicalJson()).statusCode());
        var unbound = request("POST", "/" + duplicate.runId().substring(7) + "/dossier", canonical);
        assertEquals(409, unbound.statusCode(), unbound.body());
        assertTrue(unbound.body().contains("DOSSIER_UNAVAILABLE"));
        assertEquals(404, request("POST", "/" + "a".repeat(64) + "/dossier", canonical).statusCode());
        try (var socket = new java.net.Socket("127.0.0.1", server.boundPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("POST /api/discovery-runs" + path("dossier") + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: 1048577\r\nConnection: close\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertTrue(new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII))
                .readLine().contains(" 413 "));
        }
    }

    @Test void duplicatesOnlyOneExactSeedWithoutReusingExecutionEvidence() throws Exception {
        var response = request("POST", path("duplicate"), "{\"deterministicSeed\":\"9223372036854775807\"}");
        assertEquals(201, response.statusCode(), response.body());
        var duplicate = RepresentationDiscoveryRunWorkspace.fromCanonicalJson(response.body());
        assertEquals(Long.MAX_VALUE, duplicate.plan().deterministicSeed());
        assertEquals(BUNDLE.workspace().runId(), duplicate.parentRunId());
        assertEquals("deterministicSeed", duplicate.changedPlanParameter());
        assertEquals(RepresentationDiscoveryRunOutcome.TerminalState.CREATED, duplicate.outcome().state());
        assertEquals("NOT_STARTED", duplicate.outcome().terminalReason());
        assertTrue(duplicate.artifacts().stream().allMatch(a -> a.status() == RepresentationDiscoveryArtifactReference.ArtifactStatus.NOT_PRODUCED));
        assertEquals(BUNDLE.workspace().toCanonicalJson(), request("GET", path(""), null).body());
        assertEquals(400, request("POST", path("duplicate"), "{\"deterministicSeed\":\"0\"}").statusCode());
        assertEquals(400, request("POST", path("duplicate"), "{\"deterministicSeed\":\"2\",\"objectiveId\":\"changed\"}").statusCode());
        assertEquals(400, request("POST", path("duplicate"), "{\"deterministicSeed\":\"9223372036854775808\"}").statusCode());
        assertEquals(400, request("POST", path("duplicate"), "{\"deterministicSeed\":2,\"deterministicSeed\":3}").statusCode());
    }

    @Test void rejectsAnIncompleteArtifactEvenWhenItsContentHashAndRunBindingAreValid() throws Exception {
        var content = BUNDLE.scenario().content();
        var incomplete = new TargetFreeSymPyBridgeDiscoveryScenario.ScenarioContent(content.schema(), content.scenarioId(),
            content.informationTrack(), content.search(), content.searchContentHash(), content.freeze(), null,
            content.classification(), content.followOnExecution(), content.claimBoundary());
        var json = JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        String hash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(incomplete)));
        var artifact = new TargetFreeSymPyBridgeDiscoveryScenario.ScenarioArtifact(incomplete, hash);
        var original = BUNDLE.workspace();
        var references = original.artifacts().stream().map(reference -> reference.role() == RepresentationDiscoveryArtifactReference.ArtifactRole.CANDIDATE_DOSSIERS
            ? RepresentationDiscoveryArtifactReference.available(reference.role(), reference.artifactSchema(), hash) : reference).toList();
        var run = RepresentationDiscoveryRunWorkspace.create(original.input(), original.plan(), original.outcome(), references, original.revisions());
        assertEquals(201, request("POST", "", run.toCanonicalJson()).statusCode());
        var response = request("POST", "/" + run.runId().substring(7) + "/dossier", artifact.toCanonicalJson());
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("INVALID_DOSSIER"));
    }

    @Test void rejectsEvidenceBoundToADifferentBudgetCommitment() throws Exception {
        var original = BUNDLE.workspace();
        var plan = original.plan();
        var changed = RepresentationDiscoveryRunPlan.create(plan.informationTrack(), plan.informationBoundaryHash(),
            plan.ruleInventoryHash(), plan.knowledgePackSelectionHash(), plan.knownStructureCatalogHash(),
            plan.searchStrategyId(), plan.searchProfileId(), plan.objectiveId(), "sha256:" + "f".repeat(64),
            plan.deterministicSeed(), plan.backendIdentities());
        var run = RepresentationDiscoveryRunWorkspace.create(original.input(), changed, original.outcome(), original.artifacts(), original.revisions());
        assertRejectedBeforeRetention(run, BUNDLE.scenario().toCanonicalJson());
    }

    @Test void rejectsEvidenceBoundToADifferentConfiguredWorkLimit() throws Exception {
        var original = BUNDLE.workspace();
        var outcome = original.outcome();
        var changed = RepresentationDiscoveryRunOutcome.create(outcome.state(), outcome.terminalReason(),
            outcome.configuredWork() + 1, outcome.consumedWork(), outcome.canonicalWorkLedgerHash(), outcome.runtimeDiagnosticsHash());
        var run = RepresentationDiscoveryRunWorkspace.create(original.input(), original.plan(), changed, original.artifacts(), original.revisions());
        assertRejectedBeforeRetention(run, BUNDLE.scenario().toCanonicalJson());
    }

    @ParameterizedTest @MethodSource("malformedEvidenceFields")
    void rejectsMalformedNestedEvidenceEvenWithValidOuterHashes(String field) throws Exception {
        var json = JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        var artifact = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(BUNDLE.scenario().toCanonicalJson());
        var content = (com.fasterxml.jackson.databind.node.ObjectNode) artifact.get("content");
        String[] path = field.split("\\.");
        var parent = path.length == 1 ? content : (com.fasterxml.jackson.databind.node.ObjectNode) content.get(path[0]);
        String leaf = path[path.length - 1];
        if (leaf.endsWith("Hash")) parent.put(leaf, "not-a-hash");
        else if (leaf.endsWith("Matches") || leaf.endsWith("TotalSuccessors")) parent.put(leaf, -1);
        else parent.putNull(leaf);
        String hash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(content)));
        artifact.put("contentHash", hash);
        var original = BUNDLE.workspace();
        var references = original.artifacts().stream().map(reference -> reference.role() == RepresentationDiscoveryArtifactReference.ArtifactRole.CANDIDATE_DOSSIERS
            ? RepresentationDiscoveryArtifactReference.available(reference.role(), reference.artifactSchema(), hash) : reference).toList();
        var run = RepresentationDiscoveryRunWorkspace.create(original.input(), original.plan(), original.outcome(), references, original.revisions());
        assertRejectedBeforeRetention(run, json.writeValueAsString(artifact));
    }

    private static java.util.stream.Stream<String> malformedEvidenceFields() {
        return java.util.stream.Stream.of("scenarioId", "discoveredBridge.candidateProofStatus", "discoveredBridge.minimumEvidence",
            "discoveredBridge.recognitionMode", "discoveredBridge.structureId", "discoveredBridge.consequenceId",
            "discoveredBridge.sourceProject", "discoveredBridge.sourceReference", "discoveredBridge.license",
            "freeze.disabledBoundaryHash", "freeze.enabledBoundaryHash", "freeze.candidateSetHash",
            "freeze.disabledFreezeReceiptHash", "freeze.enabledFreezeReceiptHash", "freeze.disabledCatalogHash", "freeze.enabledCatalogHash",
            "followOnExecution.disabledRuleInventoryHash", "followOnExecution.enabledRuleInventoryHash",
            "classification.disabledMatches", "classification.provisionalMatches", "classification.verifiedMatches",
            "followOnExecution.formationTotalSuccessors", "followOnExecution.disabledTotalSuccessors", "followOnExecution.enabledTotalSuccessors");
    }

    private void assertRejectedBeforeRetention(RepresentationDiscoveryRunWorkspace run, String artifact) throws Exception {
        assertEquals(201, request("POST", "", run.toCanonicalJson()).statusCode());
        String suffix = "/" + run.runId().substring(7) + "/dossier";
        var response = assertDoesNotThrow(() -> request("POST", suffix, artifact), "invalid evidence must return a structured HTTP error");
        assertEquals(400, response.statusCode(), () -> response.body().substring(0, Math.min(response.body().length(), 400)));
        assertTrue(response.body().contains("INVALID_DOSSIER"), response.body());
        assertFalse(Files.exists(temporary.resolve("runs-dossiers").resolve(run.runId().substring(7) + ".json")));
        assertEquals(404, request("GET", suffix, null).statusCode());
    }

    private static String path(String action) { return "/" + BUNDLE.workspace().runId().substring(7) + (action.isEmpty() ? "" : "/" + action); }
    private HttpResponse<String> request(String method, String suffix, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.boundPort() + "/api/discovery-runs" + suffix))
            .header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
