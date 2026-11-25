package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.domain.*;
import de.regelsuche.discovery.domain.DiscoveryDomain.*;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DomainExportWorkspaceHttpTest {
    @TempDir Path temporary;
    private WebWorkbenchServer server;
    private HttpClient client;
    private String previous;
    private final JsonMapper json = new JsonMapper();
    private DomainDiscoveryEvidence evidence;
    private Map<String, String> files;
    private String runId;

    @BeforeEach void start() throws Exception {
        var domain = new FiniteDifferenceSequenceDomain();
        evidence = new DomainDiscoveryRunner().run("retained-sequence", domain,
            DiscoverySeed.create("observed-source", domain.domainId(), "observed=1,4,9,16;holdout=25,36", "HTTP public control"),
            new DiscoveryBudget(4, 16, 32, 8, 8, 32)).evidence();
        Path source = temporary.resolve("source");
        runId = new DomainDiscoveryExport().write(source, evidence).contentHash();
        files = new TreeMap<>();
        for (String name : List.of("domain.json", "evidence.json", "lifecycle-handoff.json", "export-manifest.json")) {
            files.put(name, Base64.getEncoder().encodeToString(Files.readAllBytes(source.resolve(name))));
        }
        previous = System.getProperty("regelsuche.discovery.runs.directory");
        System.setProperty("regelsuche.discovery.runs.directory", temporary.resolve("runs").toString());
        server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), WebSecurityConfig.none());
        server.start(); client = HttpClient.newHttpClient();
    }

    @AfterEach void stop() {
        if (client != null) client.close();
        if (server != null) server.stop();
        if (previous == null) System.clearProperty("regelsuche.discovery.runs.directory");
        else System.setProperty("regelsuche.discovery.runs.directory", previous);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void importsListsLoadsAndReplaysTheOriginalSourceIdentity(boolean expressionExport) throws Exception {
        if (expressionExport) {
            var domain = new ExpressionRewriteDiscoveryDomain("x");
            evidence = new DomainDiscoveryRunner().run("retained-expression", domain,
                DiscoverySeed.create("expression-source", domain.domainId(), "x + 0", "HTTP public control"),
                new DiscoveryBudget(2, 16, 32, 8, 8, 32)).evidence();
            Path source = temporary.resolve("expression-source");
            runId = new DomainDiscoveryExport().write(source, evidence).contentHash();
            for (String name : new ArrayList<>(files.keySet())) {
                files.put(name, Base64.getEncoder().encodeToString(Files.readAllBytes(source.resolve(name))));
            }
        }
        var imported = request("POST", "", upload());
        assertEquals(201, imported.statusCode(), imported.body());
        var view = json.readTree(imported.body());
        assertEquals(runId, view.path("runId").asText());
        assertEquals(runId, imported.headers().firstValue("X-Regelsuche-Run-Id").orElseThrow());
        assertEquals("\"" + view.path("contentHash").asText().substring(7) + "\"", imported.headers().firstValue("ETag").orElseThrow());
        assertEquals(imported.body(), request("GET", path(), null).body());
        assertEquals(1, json.readTree(request("GET", "?offset=0&limit=25", null).body()).path("exports").size());
        for (var file : files.entrySet()) {
            var loaded = request("GET", path() + "/files/" + file.getKey(), null);
            assertEquals(200, loaded.statusCode(), loaded.body());
            assertArrayEquals(Base64.getDecoder().decode(file.getValue()), loaded.body().getBytes(StandardCharsets.UTF_8));
        }
        var replay = request("POST", path() + "/replay", replayRequest(view.path("contentHash").asText(), evidence.contentHash()));
        assertEquals(200, replay.statusCode(), replay.body());
        var replayed = json.readTree(replay.body());
        assertEquals(runId, replayed.path("runId").asText());
        assertEquals("IDENTICAL_CANONICAL_EVIDENCE", replayed.path("status").asText());
        assertEquals(json.readTree(evidence.toCanonicalJson()), replayed.path("evidence"));
        assertEquals(imported.body(), request("GET", path(), null).body());
        assertNotEquals(200, request("GET", "/../", null).statusCode(), "path traversal must not return a workspace");
        if (expressionExport) {
            for (String action : List.of("validate", "model-proof")) {
                var response = request("POST", path() + "/" + action,
                    replayRequest(view.path("contentHash").asText(), evidence.contentHash()));
                assertEquals(200, response.statusCode(), response.body());
                assertEquals("UNSUPPORTED", json.readTree(response.body()).path("status").asText());
            }
        }
    }

    @Test void rejectsMissingAndMalformedSourceFiles() throws Exception {
        files.remove("domain.json");
        assertEquals(400, request("POST", "", upload()).statusCode());
        files.put("domain.json", Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(400, request("POST", "", upload()).statusCode());
        assertEquals(400, request("POST", "", "{\"schema\":\"x\",\"schema\":\"y\",\"files\":{}}").statusCode());
        assertEquals(0, json.readTree(request("GET", "", null).body()).path("exports").size());
    }

    @Test void retainsLogicalExportThroughHttpWithoutIssuingProofOrReplayAuthority() throws Exception {
        String previousRun = runId;
        String previousUpload = upload();
        var domain = new ScopedLogicDiscoveryDomain(proposal -> {
            throw new AssertionError("retained workspace must not call a proof receiver");
        }, 1024);
        var proposition = new Symbol("p");
        var consequence = new Symbol("q");
        var context = Obligation.create("http-logical-source",
            List.of(new SymbolDeclaration("p", Sort.PROPOSITION), new SymbolDeclaration("q", Sort.PROPOSITION)),
            List.of(Theory.SCOPED_ANALYSIS), List.of(Predicate.holds("given", proposition),
                Predicate.holds("implies", new Call("implies", List.of(proposition, consequence)))), Goal.holds(proposition),
            RequestedEvidence.FORMAL_PROOF, new SourceProvenance("public-control", "http", SolverIr.sha256("public context")));
        evidence = new DomainDiscoveryRunner().run("http-logical-export", domain,
            DiscoverySeed.create("context", domain.domainId(), context.toCanonicalJson(), "public HTTP control"),
            new DiscoveryBudget(2, 4, 4, 4, 4, 4)).evidence();
        Path source = temporary.resolve("logical-source");
        runId = new DomainDiscoveryExport().write(source, evidence).contentHash();
        Path manifest = source.resolve("export-manifest.json");
        Files.writeString(manifest, Files.readString(manifest) + "\r\n");
        for (String name : files.keySet()) {
            files.put(name, Base64.getEncoder().encodeToString(Files.readAllBytes(source.resolve(name))));
        }
        var imported = request("POST", "", upload());
        assertEquals(201, imported.statusCode(), imported.body());
        var view = json.readTree(imported.body());
        assertEquals("scoped-logic", view.path("domainId").asText());
        assertFalse(view.path("replaySupported").asBoolean());
        assertEquals(imported.body(), request("GET", path(), null).body());
        assertEquals(1, json.readTree(request("GET", "", null).body()).path("exports").size());
        for (var file : files.entrySet()) {
            var downloaded = request("GET", path() + "/files/" + file.getKey(), null);
            assertEquals(200, downloaded.statusCode(), downloaded.body());
            assertArrayEquals(Base64.getDecoder().decode(file.getValue()), downloaded.body().getBytes(StandardCharsets.UTF_8));
        }
        String bound = replayRequest(view.path("contentHash").asText(), evidence.contentHash());
        var replay = request("POST", path() + "/replay", bound);
        assertEquals(409, replay.statusCode(), replay.body());
        assertEquals("REPLAY_NOT_REPRODUCED", json.readTree(replay.body()).path("code").asText());
        assertEquals(201, request("POST", "", previousUpload).statusCode());
        verifyScopedCandidateCheck(view.path("contentHash").asText(), previousRun);
        var assessment = request("POST", path() + "/assessment", bound);
        assertEquals(200, assessment.statusCode(), assessment.body());
        var assessed = json.readTree(assessment.body());
        assertEquals("REPLAY_NOT_CONFIGURED", assessed.path("replayStatus").asText());
        assertEquals("NOT_EVALUATED", assessed.path("proof").path("status").asText());
        assertEquals("BLOCKED", assessed.path("release").path("status").asText());
        var retainedAssessment = request("GET", path() + "/assessment/"
            + assessed.path("contentHash").asText().substring(7), null);
        assertEquals(200, retainedAssessment.statusCode(), retainedAssessment.body());
        assertEquals(assessment.body(), retainedAssessment.body());
        for (String action : List.of("validate", "model-proof")) {
            var response = request("POST", path() + "/" + action, bound);
            assertEquals(200, response.statusCode(), response.body());
            var receipt = json.readTree(response.body());
            assertEquals("UNSUPPORTED", receipt.path("status").asText());
            assertEquals("SCOPED_PROOF_REPLAY_NOT_CONFIGURED", receipt.path("detail").asText());
            var retained = request("GET", path() + "/" + action + "/"
                + receipt.path("contentHash").asText().substring(7), null);
            assertEquals(200, retained.statusCode(), retained.body());
            assertEquals(response.body(), retained.body());
        }
        assertEquals(imported.body(), request("GET", path(), null).body());
    }

    private void verifyScopedCandidateCheck(String workspaceHash, String previousRun) throws Exception {
        String candidate = evidence.candidateAttempts().getFirst().candidateHash();
        String scopedPath = path() + "/scoped-check";
        ObjectNode bound = json.createObjectNode().put("expectedWorkspaceHash", workspaceHash)
            .put("expectedEvidenceHash", evidence.contentHash()).put("candidateHash", candidate);
        String rebuildSetting = "regelsuche.lean.rebuild.project";
        String originalSetting = System.setProperty(rebuildSetting, "");
        try {
            String boundJson = bound.toString();
            String exactBody = boundJson + " ".repeat(4096 - boundJson.length());
            var response = request("POST", scopedPath, exactBody);
            assertEquals(200, response.statusCode(), response.body());
            var exactChunked = requestWithBody("POST", scopedPath, chunkedBody(exactBody));
            assertEquals(200, exactChunked.statusCode(), exactChunked.body());
            assertEquals(response.body(), exactChunked.body());
            String location = response.headers().firstValue("Location").orElseThrow();
            assertTrue(location.matches(DomainExportWorkspaceActions.BASE + scopedPath + "/[0-9a-f]{64}"), location);
            var reopened = request("GET", location.substring(DomainExportWorkspaceActions.BASE.length()), null);
            assertEquals(200, reopened.statusCode(), reopened.body());
            assertEquals(response.body(), reopened.body());
            assertEquals(404, request("GET", scopedPath + "/" + "0".repeat(64), null).statusCode());
            String oversizedBody = exactBody + " ";
            for (var publisher : List.of(HttpRequest.BodyPublishers.ofString(oversizedBody), chunkedBody(oversizedBody))) {
                var oversized = requestWithBody("POST", scopedPath, publisher);
                assertEquals(413, oversized.statusCode(), oversized.body());
                var rejection = json.readTree(oversized.body());
                assertEquals("PAYLOAD_TOO_LARGE", rejection.path("code").asText());
                assertEquals(4096, rejection.path("limitBytes").asInt());
            }
            var report = json.readTree(response.body());
            assertEquals("regelsuche.scoped-workspace-candidate-check/v1", report.path("schema").asText());
            assertEquals(runId, response.headers().firstValue("X-Regelsuche-Run-Id").orElseThrow());
            assertEquals(runId, report.at("/source/runId").asText());
            assertEquals(workspaceHash, report.at("/source/workspaceHash").asText());
            assertEquals(evidence.contentHash(), report.at("/source/evidenceHash").asText());
            assertEquals(evidence.seed().contentHash(), report.at("/source/seedHash").asText());
            assertEquals(candidate, report.at("/source/candidateHash").asText());
            assertEquals(evidence.states().getLast().stateHash(), report.at("/source/stateHash").asText());
            assertEquals("NONE_FOUND", report.at("/counterexample/status").asText());
            assertEquals(4, report.at("/counterexample/attempts").asInt());
            assertEquals("UNSUPPORTED", report.at("/proof/status").asText());
            assertFalse(report.at("/proof/backendConfigured").asBoolean());
            assertFalse(report.path("proof").has("kernelHash"));
            var altered = ((ObjectNode) report).deepCopy().put("proposalHash", SolverIr.sha256("different proposal"));
            String alteredBytes = altered.toString();
            String alteredDigest = SolverIr.sha256(alteredBytes).substring(7);
            var receipts = temporary.resolve("runs-domain-exports-scoped-checks").resolve(runId.substring(7));
            Files.writeString(receipts.resolve(alteredDigest + ".json"), alteredBytes);
            assertEquals(500, request("GET", scopedPath + "/" + alteredDigest, null).statusCode());
            var forged = ((ObjectNode) report).deepCopy();
            forged.putObject("proof").put("backendConfigured", true).put("status", "CONFIRMED")
                .put("kernelHash", SolverIr.sha256("invented kernel")).put("rebuildHash", SolverIr.sha256("invented rebuild"));
            String forgedBytes = forged.toString();
            String forgedDigest = SolverIr.sha256(forgedBytes).substring(7);
            Files.writeString(receipts.resolve(forgedDigest + ".json"), forgedBytes);
            assertEquals(500, request("GET", scopedPath + "/" + forgedDigest, null).statusCode());
            forged.putObject("proof").put("backendConfigured", true).put("status", "REFUTED");
            String refutedBytes = forged.toString();
            String refutedDigest = SolverIr.sha256(refutedBytes).substring(7);
            Files.writeString(receipts.resolve(refutedDigest + ".json"), refutedBytes);
            assertEquals(500, request("GET", scopedPath + "/" + refutedDigest, null).statusCode());
            var tamperedFile = receipts.resolve(location.substring(location.lastIndexOf('/') + 1) + ".json");
            String otherRun = previousRun.substring(7);
            String otherPath = "/" + otherRun + "/scoped-check/" + tamperedFile.getFileName().toString().substring(0, 64);
            assertEquals(404, request("GET", otherPath, null).statusCode());
            var otherDirectory = Files.createDirectories(receipts.resolveSibling(otherRun));
            Files.copy(tamperedFile, otherDirectory.resolve(tamperedFile.getFileName()));
            assertEquals(500, request("GET", otherPath, null).statusCode());
            Files.writeString(tamperedFile, " ", StandardOpenOption.APPEND);
            assertEquals(500, request("GET", location.substring(DomainExportWorkspaceActions.BASE.length()), null).statusCode());
            assertEquals(400, request("POST", scopedPath, bound.deepCopy().put("candidateHash", "sha256:" + "f".repeat(64)).toString()).statusCode());
            assertEquals(409, request("POST", scopedPath, bound.deepCopy().put("expectedWorkspaceHash", evidence.contentHash()).toString()).statusCode());
            bound.remove("candidateHash");
            assertEquals(400, request("POST", scopedPath, bound.toString()).statusCode());
            assertEquals(405, request("GET", scopedPath, null).statusCode());
        } finally {
            if (originalSetting == null) System.clearProperty(rebuildSetting);
            else System.setProperty(rebuildSetting, originalSetting);
        }
    }

    @Test void preservesFirstExactBytesWhenManifestIdentityIsReused() throws Exception {
        var first = request("POST", "", upload());
        assertEquals(201, first.statusCode(), first.body());
        String manifest = new String(Base64.getDecoder().decode(files.get("export-manifest.json")), StandardCharsets.UTF_8);
        files.put("export-manifest.json", Base64.getEncoder().encodeToString((manifest + "\n").getBytes(StandardCharsets.UTF_8)));
        var collision = request("POST", "", upload());
        assertEquals(409, collision.statusCode(), collision.body());
        assertTrue(collision.body().contains("SOURCE_IDENTITY_CONFLICT"), collision.body());
        assertEquals(first.body(), request("GET", path(), null).body());
    }

    @Test void rejectsReplayRequestsBoundToAnotherSourceAndChangedRetainedFiles() throws Exception {
        var imported = request("POST", "", upload());
        assertEquals(201, imported.statusCode(), imported.body());
        String hash = json.readTree(imported.body()).path("contentHash").asText();
        var foreign = request("POST", path() + "/replay", replayRequest(hash, "sha256:" + "f".repeat(64)));
        assertEquals(409, foreign.statusCode(), foreign.body());
        assertTrue(foreign.body().contains("SOURCE_BINDING_MISMATCH"));
        Path retained = temporary.resolve("runs-domain-exports").resolve(runId.substring(7)).resolve("export/evidence.json");
        Files.writeString(retained, "{}");
        assertEquals(400, request("GET", path(), null).statusCode());
        assertEquals(400, request("GET", path() + "/files/evidence.json", null).statusCode());
    }

    @Test void rejectsDeclaredOversizeBeforeReadingTheBody() throws Exception {
        try (var socket = new java.net.Socket("127.0.0.1", server.boundPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("POST /api/discovery-domains/exports HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: 1048577\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            assertTrue(status.contains(" 413 "), status);
        }
    }

    @Test void malformedReplayBodiesReturnStructuredBadRequestsBeforeExecution() throws Exception {
        var imported = request("POST", "", upload());
        assertEquals(201, imported.statusCode(), imported.body());
        for (String body : List.of("{", "{}", "{\"expectedWorkspaceHash\":null,\"expectedEvidenceHash\":null}",
                "{\"expectedWorkspaceHash\":\"bad\",\"expectedEvidenceHash\":\"bad\"}",
                "{\"expectedWorkspaceHash\":\"x\",\"expectedWorkspaceHash\":\"y\",\"expectedEvidenceHash\":\"z\"}")) {
            var response = request("POST", path() + "/replay", body);
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"), response.body());
            assertEquals("INVALID_DOMAIN_EXPORT", json.readTree(response.body()).path("code").asText());
        }
        var plain = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.boundPort()
                + "/api/discovery-domains/exports" + path() + "/replay"))
            .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(
                replayRequest(json.readTree(imported.body()).path("contentHash").asText(), evidence.contentHash()))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(400, plain.statusCode(), plain.body());
        assertEquals(imported.body(), request("GET", path(), null).body());
    }

    @Test void validatesBothExistingDomainsAndDownloadsASeparateBoundReceipt() throws Exception {
        for (boolean recurrence : new boolean[]{false,true}) {
            if (recurrence) {
                var domain = new LinearRecurrenceSequenceDomain();
                evidence = new DomainDiscoveryRunner().run("retained-recurrence",domain,
                    DiscoverySeed.create("recurrence-source",domain.domainId(),"observed=2,3,5,8,13,21;holdout=34,55,89;maximumOrder=3","HTTP public control"),
                    new DiscoveryBudget(4,16,32,8,8,32)).evidence();
                Path source = temporary.resolve("recurrence");
                runId = new DomainDiscoveryExport().write(source,evidence).contentHash();
                for (String name : files.keySet()) files.put(name,Base64.getEncoder().encodeToString(Files.readAllBytes(source.resolve(name))));
            }
            var imported = request("POST","",upload());
            assertEquals(201,imported.statusCode(),imported.body());
            String workspaceHash = json.readTree(imported.body()).path("contentHash").asText();
            var response = request("POST",path()+"/validate",replayRequest(workspaceHash,evidence.contentHash()));
            assertEquals(200,response.statusCode(),response.body());
            var receipt = json.readTree(response.body());
            assertEquals(DomainDownstreamValidation.SCHEMA,receipt.path("schema").asText());
            assertEquals("CONFIRMED_FINITE_DATA",receipt.path("status").asText());
            assertEquals(workspaceHash,receipt.path("source").path("workspaceHash").asText());
            assertEquals(evidence.contentHash(),receipt.path("source").path("evidenceHash").asText());
            assertEquals("NOT_PRODUCED",receipt.path("universalProofStatus").asText());
            assertEquals("NOT_EVALUATED",receipt.path("publicEvidenceStatus").asText());
            assertTrue(response.headers().firstValue("Content-Disposition").orElseThrow().contains(receipt.path("contentHash").asText().substring(7)));
            assertEquals("\""+receipt.path("contentHash").asText().substring(7)+"\"",response.headers().firstValue("ETag").orElseThrow());
            assertEquals(runId,response.headers().firstValue("X-Regelsuche-Run-Id").orElseThrow());
            String receiptDigest = receipt.path("contentHash").asText().substring(7);
            String receiptPath = path() + "/validate/" + receiptDigest;
            assertEquals("/api/discovery-domains/exports" + receiptPath,
                response.headers().firstValue("Location").orElse(""));
            var loaded = request("GET", receiptPath, null);
            assertEquals(200, loaded.statusCode(), loaded.body());
            assertEquals(response.body(), loaded.body());
            for (String header : List.of("Content-Disposition", "ETag", "X-Regelsuche-Run-Id")) {
                assertEquals(response.headers().firstValue(header), loaded.headers().firstValue(header));
            }
            var retry = request("POST",path()+"/validate",replayRequest(workspaceHash,evidence.contentHash()));
            assertEquals(200, retry.statusCode(), retry.body());
            assertEquals(response.body(), retry.body());
            try (var retained = Files.list(validationDirectory())) {
                assertEquals(List.of(receiptDigest + ".json"), retained.map(p -> p.getFileName().toString()).toList());
            }
            assertEquals(imported.body(),request("GET",path(),null).body());
            for (var file : files.entrySet()) assertArrayEquals(Base64.getDecoder().decode(file.getValue()),
                request("GET",path()+"/files/"+file.getKey(),null).body().getBytes(StandardCharsets.UTF_8));
            try (var retained = Files.list(temporary.resolve("runs-domain-exports").resolve(runId.substring(7)))) {
                assertEquals(Set.of("export","workspace.json"),retained.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
            }
        }
    }

    @Test void refusesMissingForeignAndCorruptRetainedReceipts() throws Exception {
        var imported = request("POST", "", upload());
        assertEquals(201, imported.statusCode(), imported.body());
        String workspaceHash = json.readTree(imported.body()).path("contentHash").asText();
        var receipt = request("POST", path() + "/validate", replayRequest(workspaceHash, evidence.contentHash()));
        assertEquals(200, receipt.statusCode(), receipt.body());
        String digest = json.readTree(receipt.body()).path("contentHash").asText().substring(7);
        String receiptPath = path() + "/validate/" + digest;
        Path retained = validationDirectory().resolve(digest + ".json");
        assertEquals(404, request("GET", path() + "/validate/" + "f".repeat(64), null).statusCode());
        assertEquals(400, request("GET", path() + "/validate/invalid", null).statusCode());
        assertEquals(405, request("POST", receiptPath, "{}").statusCode());

        var domain = new FiniteDifferenceSequenceDomain();
        var foreign = new DomainDiscoveryRunner().run("foreign-source", domain, evidence.seed(),
            evidence.budget()).evidence();
        Path source = temporary.resolve("foreign");
        String foreignRun = new DomainDiscoveryExport().write(source, foreign).contentHash().substring(7);
        for (String name : files.keySet()) {
            files.put(name, Base64.getEncoder().encodeToString(Files.readAllBytes(source.resolve(name))));
        }
        assertEquals(201, request("POST", "", upload()).statusCode());
        String foreignPath = "/" + foreignRun + "/validate/" + digest;
        assertEquals(404, request("GET", foreignPath, null).statusCode());
        Path foreignDirectory = validationDirectory().resolveSibling(foreignRun);
        Files.createDirectories(foreignDirectory);
        Files.copy(retained, foreignDirectory.resolve(retained.getFileName()));
        assertEquals(500, request("GET", foreignPath, null).statusCode());

        for (String corrupt : List.of(receipt.body() + "\n",
                receipt.body().replace("CONFIRMED_FINITE_DATA", "REFUTED_FINITE_DATA"),
                " ".repeat(1_048_577))) {
            Files.writeString(retained, corrupt);
            assertEquals(500, request("GET", receiptPath, null).statusCode());
        }
        var retry = request("POST", path() + "/validate", replayRequest(workspaceHash, evidence.contentHash()));
        assertEquals(500, retry.statusCode(), retry.body());
        assertEquals(1_048_577, Files.size(retained));

        Files.delete(retained);
        Path linked = Files.writeString(temporary.resolve("linked-receipt.json"), receipt.body());
        Files.createSymbolicLink(retained, linked);
        assertEquals(500, request("GET", receiptPath, null).statusCode());
        Files.delete(retained);
        Files.writeString(retained, receipt.body());
        assertEquals(200, request("GET", receiptPath, null).statusCode());

        assertRehashedReceiptTamperingIsRejected(receipt.body());

        Path original = temporary.resolve("runs-domain-exports").resolve(runId.substring(7))
            .resolve("export/evidence.json");
        Files.writeString(original, " ", StandardOpenOption.APPEND);
        assertEquals(400, request("GET", receiptPath, null).statusCode());
    }


    private void assertRehashedReceiptTamperingIsRejected(String receiptBody) throws Exception {
        for (String field : List.of("check", "artifacts", "status", "promotionStatus", "checkBudget",
                "rows", "work", "counterexampleStatus", "checkerContract", "firstCounterexampleIndex")) {
            var malformed = (ObjectNode) json.readTree(receiptBody);
            corruptRetainedReceiptField(malformed, field);
            String mutatedDigest = rehash(malformed);
            Files.writeString(validationDirectory().resolve(mutatedDigest + ".json"), json.writeValueAsString(malformed));
            assertEquals(500, request("GET", path() + "/validate/" + mutatedDigest, null).statusCode(), field);
        }
    }

    private void corruptRetainedReceiptField(ObjectNode malformed, String field) throws Exception {
        switch (field) {
            case "status" -> malformed.put(field, "FUTURE_STATUS");
            case "promotionStatus" -> malformed.put(field, "APPROVED");
            case "checkBudget" -> malformed.putObject(field);
            case "rows", "work", "counterexampleStatus", "checkerContract", "firstCounterexampleIndex" ->
                corruptFiniteCheckField(malformed, field);
            default -> malformed.remove(field);
        }
    }

    private void corruptFiniteCheckField(ObjectNode malformed, String field) throws Exception {
        var check = (ObjectNode) malformed.path("check");
        switch (field) {
            case "counterexampleStatus" -> {
                check.put(field, "FOUND").put("firstCounterexampleIndex", 0);
                malformed.put(field, "FOUND");
            }
            case "checkerContract" -> check.put(field, DomainFiniteDataCheck.RESIDUAL_CONTRACT);
            case "firstCounterexampleIndex" -> check.put(field, 1);
            default -> check.remove(field);
        }
        String checkHash = rehash(check);
        for (var role : malformed.path("artifacts")) {
            if (Set.of("VALIDATION_EVIDENCE", "FINITE_COUNTEREXAMPLE_CHECK").contains(role.path("role").asText())) {
                ((ObjectNode) role).put("targetContentHash", "sha256:" + checkHash);
            }
        }
    }

    @Test void validationRequiresExactRequestBindingsAndThePostMethod() throws Exception {
        var imported = request("POST","",upload());
        assertEquals(201,imported.statusCode(),imported.body());
        String hash = json.readTree(imported.body()).path("contentHash").asText();
        for (var hashes : List.of(List.of("sha256:"+"f".repeat(64),evidence.contentHash()),List.of(hash,"sha256:"+"f".repeat(64)))) {
            var mismatch = request("POST",path()+"/validate",replayRequest(hashes.getFirst(),hashes.getLast()));
            assertEquals(409,mismatch.statusCode(),mismatch.body());
            assertEquals("SOURCE_BINDING_MISMATCH",json.readTree(mismatch.body()).path("code").asText());
        }
        for (String body : List.of("{","{}","{\"expectedWorkspaceHash\":null,\"expectedEvidenceHash\":null}",
                replayRequest(hash,evidence.contentHash()).replace("}",",\"candidate\":\"invented\"}"),
                "{\"expectedWorkspaceHash\":\"x\",\"expectedWorkspaceHash\":\"y\",\"expectedEvidenceHash\":\"z\"}")) {
            assertEquals(400,request("POST",path()+"/validate",body).statusCode());
        }
        assertEquals(405,request("GET",path()+"/validate",null).statusCode());
        assertEquals(413,request("POST",path()+"/validate",replayRequest(hash,evidence.contentHash())+" ".repeat(4096)).statusCode());
        assertEquals(imported.body(),request("GET",path(),null).body());
    }

    private String path() { return "/" + runId.substring(7); }
    private String rehash(ObjectNode value) throws Exception {
        value.remove("contentHash");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));
        value.put("contentHash", "sha256:" + digest);
        return digest;
    }
    private Path validationDirectory() {
        return temporary.resolve("runs-domain-exports-validations").resolve(runId.substring(7));
    }
    private String upload() throws Exception { return json.writeValueAsString(Map.of("schema", "regelsuche.domain-export-upload/v1", "files", files)); }
    private String replayRequest(String workspaceHash, String evidenceHash) throws Exception {
        return json.writeValueAsString(Map.of("expectedWorkspaceHash", workspaceHash, "expectedEvidenceHash", evidenceHash));
    }
    private HttpResponse<String> request(String method, String suffix, String body) throws Exception {
        return requestWithBody(method, suffix, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    }
    private HttpResponse<String> requestWithBody(String method, String suffix, HttpRequest.BodyPublisher body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.boundPort() + "/api/discovery-domains/exports" + suffix))
            .header("Content-Type", "application/json").method(method, body).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpRequest.BodyPublisher chunkedBody(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes));
    }
}
