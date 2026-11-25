package de.regelsuche.web;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import de.regelsuche.discovery.domain.*;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.solver.ir.ScopedProofDag;
import de.regelsuche.solver.ir.ScopedProofExecutor;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.portfolio.CheckedLeanScopedProofExecutor;
import de.regelsuche.solver.portfolio.LeanEvidenceRebuilder;
import de.regelsuche.solver.portfolio.LeanSolverBackend;
import de.regelsuche.solver.portfolio.LeanSourceRenderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Schema-dispatched retained exports under the existing domain API context. */
final class DomainExportWorkspaceActions {
    static final String BASE = "/api/discovery-domains/exports";
    private static final String SCOPED_CHECK = "scoped-check";
    private static final Set<String> BOUND_ACTIONS = Set.of("replay", "validate", "model-proof", "assessment", SCOPED_CHECK);
    private static final Set<String> SOURCE_FIELDS = Set.of("expectedWorkspaceHash", "expectedEvidenceHash");
    private static final Set<String> SCOPED_SOURCE_FIELDS = Set.of("expectedWorkspaceHash", "expectedEvidenceHash", "candidateHash");
    private static final Semaphore SCOPED_CHECK_SLOT = new Semaphore(1);
    private static final JsonMapper JSON = new JsonMapper();
    private DomainExportWorkspaceActions() { }

    static void handle(HttpExchange exchange, Path directory, int maxBytes) throws IOException {
        String suffix = exchange.getRequestURI().getPath().substring(BASE.length());
        if (suffix.endsWith("/")) suffix = suffix.substring(0, suffix.length() - 1);
        try {
            boolean post = exchange.getRequestMethod().equals("POST");
            String action = suffix.substring(suffix.lastIndexOf('/') + 1);
            var boundRequest = post && BOUND_ACTIONS.contains(action)
                ? readBoundRequest(exchange, maxBytes, SCOPED_CHECK.equals(action) ? SCOPED_SOURCE_FIELDS : SOURCE_FIELDS)
                : Map.<String, Object>of();
            var repository = new DomainExportWorkspaceRepository(directory);
            if (suffix.isEmpty()) {
                handleIndex(exchange, repository, post, maxBytes);
                return;
            }
            String[] path = suffix.substring(1).split("/", -1);
            if (!path[0].matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid export digest");
            if (path.length == 3 && Set.of("assessment", "validate", "model-proof", SCOPED_CHECK).contains(path[1])) {
                sendRetainedReceipt(exchange, repository, directory, "sha256:" + path[0], path[1], path[2]);
                return;
            }
            var found = findSource(repository, "sha256:" + path[0], path.length == 2 && path[1].equals("model-proof"));
            if (found.isEmpty()) { error(exchange, 404, "DOMAIN_EXPORT_NOT_FOUND", "retained export not found"); return; }
            var workspace = found.get();
            if (path.length == 1) sendWorkspace(exchange, 200, workspace);
            else if (path.length == 2 && BOUND_ACTIONS.contains(path[1])) {
                handleBoundAction(exchange, repository, directory, workspace, boundRequest, path[1]);
            } else if (path.length == 3 && path[1].equals("files")) {
                sendOriginalFile(exchange, workspace, path[2]);
            } else error(exchange, 404, "DOMAIN_EXPORT_ROUTE_NOT_FOUND", "unsupported export action");
        } catch (DomainExportWorkspaceRepository.SourceIdentityConflictException exception) {
            error(exchange, 409, "SOURCE_IDENTITY_CONFLICT", exception.getMessage());
        } catch (StreamingJsonRequestBody.MalformedJsonRequestException exception) {
            error(exchange, 400, "INVALID_DOMAIN_EXPORT", "invalid JSON bound request");
        } catch (IllegalArgumentException | DomainDiscoveryExportVerifier.ExportVerificationException exception) {
            error(exchange, 400, "INVALID_DOMAIN_EXPORT", exception.getMessage());
        } catch (IllegalStateException exception) {
            error(exchange, 500, "DOMAIN_EXPORT_REPOSITORY_FAILURE", exception.getMessage());
        }
    }

    private static void sendRetainedReceipt(HttpExchange exchange, DomainExportWorkspaceRepository repository,
            Path directory, String runId, String action, String digest) throws IOException {
        String hash = "sha256:" + digest;
        Optional<byte[]> bytes;
        String prefix;
        String missing;
        switch (action) {
            case SCOPED_CHECK -> {
                bytes = new ScopedCheckReceipts(directory).find(runId, hash);
                prefix = "scoped-check";
                missing = "SCOPED_CHECK_NOT_FOUND";
            }
            case "assessment" -> {
                bytes = new DomainSnapshotAssessmentRepository(directory).find(runId, hash);
                prefix = "domain-assessment";
                missing = "DOMAIN_ASSESSMENT_NOT_FOUND";
            }
            case "model-proof" -> {
                bytes = new DomainModelProofRepository(directory).find(runId, hash);
                prefix = "domain-model-proof";
                missing = "DOMAIN_MODEL_PROOF_NOT_FOUND";
            }
            case "validate" -> {
                bytes = repository.findValidation(runId, hash);
                prefix = "domain-validation";
                missing = "DOMAIN_VALIDATION_NOT_FOUND";
            }
            default -> throw new IllegalArgumentException("unsupported receipt action");
        }
        if (bytes.isEmpty()) error(exchange, 404, missing, "retained export or requested receipt not found");
        else sendReceipt(exchange, runId, digest, bytes.orElseThrow(), prefix);
    }

    private static Optional<DomainExportWorkspace> findSource(DomainExportWorkspaceRepository repository,
            String runId, boolean modelProof) throws IOException {
        try { return repository.find(runId); }
        catch (IllegalArgumentException | DomainDiscoveryExportVerifier.ExportVerificationException exception) {
            if (modelProof) throw new IllegalStateException("invalid retained model proof source", exception);
            throw exception;
        }
    }

    private static void handleBoundAction(HttpExchange exchange, DomainExportWorkspaceRepository repository,
            Path directory, DomainExportWorkspace workspace,
            Map<String, Object> boundRequest, String action) throws IOException {
        if (!workspace.contentHash().equals(boundRequest.get("expectedWorkspaceHash"))
                || !workspace.evidence().contentHash().equals(boundRequest.get("expectedEvidenceHash"))) {
            error(exchange, 409, "SOURCE_BINDING_MISMATCH", "request belongs to another source view"); return;
        }
        if (action.equals(SCOPED_CHECK)) {
            checkScopedCandidate(exchange, directory, workspace, (String) boundRequest.get("candidateHash"));
            return;
        }
        if (action.equals("assessment")) {
            var receipt = DomainSnapshotAssessment.assess(workspace);
            new DomainSnapshotAssessmentRepository(directory).retain(workspace, receipt);
            String digest = receipt.contentHash().substring(7);
            exchange.getResponseHeaders().set("Location", BASE + "/" + workspace.runId().substring(7) + "/assessment/" + digest);
            sendReceipt(exchange, workspace.runId(), digest, receipt.toCanonicalJson().getBytes(StandardCharsets.UTF_8), "domain-assessment");
            return;
        }
        if (action.equals("model-proof")) {
            var receipt = DomainModelProof.prove(workspace);
            new DomainModelProofRepository(directory).retain(workspace, receipt);
            String digest = receipt.contentHash().substring(7);
            exchange.getResponseHeaders().set("Location", BASE + "/" + workspace.runId().substring(7) + "/model-proof/" + digest);
            sendReceipt(exchange, workspace.runId(), digest, receipt.toCanonicalJson().getBytes(StandardCharsets.UTF_8), "domain-model-proof");
            return;
        }
        if (action.equals("validate")) {
            var receipt = DomainDownstreamValidation.validate(workspace);
            repository.retainValidation(workspace, receipt);
            String digest = receipt.contentHash().substring(7);
            exchange.getResponseHeaders().set("Location", BASE + "/" + workspace.runId().substring(7) + "/validate/" + digest);
            sendReceipt(exchange, workspace.runId(), digest, receipt.toCanonicalJson().getBytes(StandardCharsets.UTF_8), "domain-validation");
            return;
        }
        try {
            var replayed = workspace.replay();
            var result = JSON.createObjectNode().put("schema", DomainExportWorkspace.REPLAY_SCHEMA)
                .put("runId", workspace.runId()).put("workspaceContentHash", workspace.contentHash())
                .put("sourceEvidenceHash", workspace.evidence().contentHash()).put("status", "IDENTICAL_CANONICAL_EVIDENCE");
            result.set("evidence", JSON.readTree(replayed.toCanonicalJson()));
            exchange.getResponseHeaders().set("X-Regelsuche-Run-Id", workspace.runId());
            send(exchange, 200, JSON.writeValueAsString(result));
        } catch (IllegalStateException exception) { error(exchange, 409, "REPLAY_NOT_REPRODUCED", exception.getMessage()); }
    }

    private static void checkScopedCandidate(HttpExchange exchange, Path directory, DomainExportWorkspace workspace, String candidateHash) throws IOException {
        if (!SCOPED_CHECK_SLOT.tryAcquire()) {
            error(exchange, 429, "SCOPED_CHECK_BUSY", "another scoped candidate check is running");
            return;
        }
        try {
            Thread.startVirtualThread(() -> finishScopedCheck(exchange, directory, workspace, candidateHash));
        } catch (RuntimeException | Error failure) {
            SCOPED_CHECK_SLOT.release();
            throw failure;
        }
    }

    private static void finishScopedCheck(HttpExchange exchange, Path directory, DomainExportWorkspace workspace, String candidateHash) {
        try {
            try {
                byte[] bytes;
                String digest;
                try {
                    var checked = scopedCheck(workspace, candidateHash);
                    bytes = JSON.writeValueAsBytes(checked.report());
                    digest = new ScopedCheckReceipts(directory).retain(workspace, bytes, checked.certificate());
                } catch (IOException failure) {
                    throw new IllegalStateException("unable to retain scoped receipt", failure);
                } finally { SCOPED_CHECK_SLOT.release(); }
                exchange.getResponseHeaders().set("Location", BASE + "/" + workspace.runId().substring(7)
                    + "/" + SCOPED_CHECK + "/" + digest);
                sendReceipt(exchange, workspace.runId(), digest, bytes, SCOPED_CHECK);
            } catch (IllegalArgumentException invalid) {
                error(exchange, 400, "INVALID_DOMAIN_EXPORT", invalid.getMessage());
            } catch (RuntimeException failure) {
                error(exchange, 500, "SCOPED_CHECK_FAILED", "scoped candidate check could not complete");
            }
        } catch (IOException disconnected) {
            exchange.close();
        }
    }

    private record CheckedCandidate(ObjectNode report, Optional<ScopedLogicDiscoveryDomain.Certificate> certificate) {}

    private static CheckedCandidate scopedCheck(DomainExportWorkspace workspace, String candidateHash) {
        String project = System.getProperty("regelsuche.lean.project", System.getenv().getOrDefault("REGELSUCHE_LEAN_PROJECT", ""));
        String rebuild = System.getProperty("regelsuche.lean.rebuild.project", System.getenv().getOrDefault("REGELSUCHE_LEAN_REBUILD_PROJECT", ""));
        boolean configured = !project.isBlank() && !rebuild.isBlank();
        ScopedProofExecutor receiver;
        if (configured) {
            Path root = Path.of(System.getProperty("regelsuche.proof.evidence", "proof-evidence"));
            receiver = new CheckedLeanScopedProofExecutor(new LeanSolverBackend(Path.of(project), root),
                new LeanEvidenceRebuilder(Path.of(rebuild), root));
        } else {
            receiver = new ScopedProofExecutor() {
                @Override public boolean supports(SolverIr.Obligation obligation) {
                    return new LeanSourceRenderer().render(obligation).supported();
                }
                @Override public VerifiedReplay verify(ScopedProofDag dag) {
                    return new VerifiedReplay(dag.hash(), SolverIr.ResultStatus.UNSUPPORTED, "", "", null, "", null);
                }
            };
        }
        var checked = ScopedLogicWorkspaceCheck.check(workspace, candidateHash, receiver);
        var result = JSON.createObjectNode().put("schema", "regelsuche.scoped-workspace-candidate-check/v1");
        result.set("source", JSON.valueToTree(checked.source()));
        result.put("obligationHash", checked.proposal().obligation().contentHash()).put("proposalHash", checked.proposal().hash());
        result.set("counterexample", JSON.valueToTree(checked.counterexample()));
        result.put("retainedWitness", checked.retainedWitness().name());
        var proof = result.putObject("proof").put("backendConfigured", configured).put("status", "NOT_RUN");
        checked.freshProof().ifPresent(evaluation -> {
            proof.put("status", evaluation.status().name());
            if (evaluation.certificate() != null) {
                proof.put("kernelHash", evaluation.certificate().kernelHash());
                proof.put("rebuildHash", evaluation.certificate().rebuildHash());
            }
        });
        return new CheckedCandidate(result, checked.freshProof().map(DiscoveryDomain.Evaluation::certificate));
    }

    private static void sendReceipt(HttpExchange exchange, String runId,
            String digest, byte[] bytes, String filePrefix) throws IOException {
        exchange.getResponseHeaders().set("X-Regelsuche-Run-Id", runId);
        exchange.getResponseHeaders().set("ETag", "\"" + digest + "\"");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filePrefix + "-" + digest + ".json\"");
        send(exchange, 200, bytes);
    }

    private static void sendOriginalFile(HttpExchange exchange, DomainExportWorkspace workspace,
            String fileName) throws IOException {
        byte[] bytes;
        if (fileName.equals(DomainDiscoveryExport.MANIFEST_FILE_NAME)) bytes = workspace.originalManifestBytes();
        else {
            var role = Arrays.stream(DomainDiscoveryExport.ArtifactRole.values()).filter(value -> value.fileName().equals(fileName)).findFirst();
            if (role.isEmpty()) { error(exchange, 404, "DOMAIN_EXPORT_FILE_NOT_FOUND", "unsupported original file"); return; }
            bytes = workspace.originalArtifactBytes(role.get());
        }
        exchange.getResponseHeaders().set("X-Regelsuche-Run-Id", workspace.runId());
        send(exchange, 200, bytes);
    }

    private static void handleIndex(HttpExchange exchange, DomainExportWorkspaceRepository repository,
            boolean post, int maxBytes) throws IOException {
        if (post) {
            var workspace = repository.importSnapshot(read(exchange, maxBytes));
            exchange.getResponseHeaders().set("Location", BASE + "/" + workspace.runId().substring(7));
            sendWorkspace(exchange, 201, workspace);
        } else {
            var page = repository.list(queryInt(exchange, "offset", 0), queryInt(exchange, "limit", 25));
            String body = new JsonWriter().beginObject().property("schema", "regelsuche.domain-export-workspace-index/v1")
                .property("offset", page.offset()).property("limit", page.limit()).property("total", page.total())
                .array("exports", array -> page.exports().forEach(workspace -> array.objectValue(item ->
                    item.property("runId", workspace.runId()).property("workspaceContentHash", workspace.contentHash())
                        .property("domainId", workspace.evidence().descriptor().domainId()).property("domainRevision", workspace.evidence().descriptor().revision())
                        .property("campaignId", workspace.evidence().campaignId()).property("outcome", workspace.evidence().outcome().name()))))
                .endObject().toString();
            send(exchange, 200, body);
        }
    }

    private static Map<String, Object> readBoundRequest(HttpExchange exchange, int maxBytes, Set<String> fields) throws IOException {
        requireJson(exchange);
        var request = new StreamingJsonRequestBody(Math.min(maxBytes, 4096)).readObject(exchange);
        if (!request.keySet().equals(fields)
                || request.values().stream().anyMatch(value -> !(value instanceof String hash) || !hash.matches("sha256:[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("action requires exact bound SHA-256 identities: " + fields.stream().sorted().toList());
        }
        return request;
    }

    private static byte[] read(HttpExchange exchange, int maxBytes) throws IOException {
        requireJson(exchange);
        try (var input = BoundedRequestBody.open(exchange, Math.min(maxBytes, DomainExportWorkspaceRepository.MAX_UPLOAD_BYTES))) { return input.readAllBytes(); }
    }

    private static void requireJson(HttpExchange exchange) {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) throw new IllegalArgumentException("Content-Type must be application/json");
    }

    private static int queryInt(HttpExchange exchange, String key, int fallback) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return fallback;
        String result = null;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (!Set.of("offset", "limit").contains(pair[0]) || pair.length != 2) throw new IllegalArgumentException("unsupported list query");
            if (pair[0].equals(key)) {
                if (result != null || !pair[1].matches("[0-9]+")) throw new IllegalArgumentException("invalid list query");
                result = pair[1];
            }
        }
        return result == null ? fallback : Integer.parseInt(result);
    }

    private static void sendWorkspace(HttpExchange exchange, int status, DomainExportWorkspace workspace) throws IOException {
        exchange.getResponseHeaders().set("X-Regelsuche-Run-Id", workspace.runId());
        exchange.getResponseHeaders().set("ETag", "\"" + workspace.contentHash().substring(7) + "\"");
        send(exchange, status, workspace.toCanonicalJson());
    }
    private static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        send(exchange, status, new JsonWriter().beginObject().property("code", code).property("message", message).endObject().toString());
    }
    private static void send(HttpExchange exchange, int status, String body) throws IOException { send(exchange, status, body.getBytes(StandardCharsets.UTF_8)); }
    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }
}
