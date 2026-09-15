package de.regelsuche.web;

import com.sun.net.httpserver.HttpExchange;
import de.regelsuche.discovery.domain.*;
import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Schema-dispatched retained exports under the existing domain API context. */
final class DomainExportWorkspaceActions {
    static final String BASE = "/api/discovery-domains/exports";
    private DomainExportWorkspaceActions() { }

    static void handle(HttpExchange exchange, Path directory, int maxBytes) throws IOException {
        String suffix = exchange.getRequestURI().getPath().substring(BASE.length());
        if (suffix.endsWith("/")) suffix = suffix.substring(0, suffix.length() - 1);
        try {
            boolean post = exchange.getRequestMethod().equals("POST");
            var boundRequest = post && (suffix.endsWith("/replay") || suffix.endsWith("/validate"))
                ? readBoundRequest(exchange, maxBytes) : Map.<String, Object>of();
            var repository = new DomainExportWorkspaceRepository(directory);
            if (suffix.isEmpty()) {
                handleIndex(exchange, repository, post, maxBytes);
                return;
            }
            String[] path = suffix.substring(1).split("/", -1);
            if (!path[0].matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid export digest");
            var found = repository.find("sha256:" + path[0]);
            if (found.isEmpty()) { error(exchange, 404, "DOMAIN_EXPORT_NOT_FOUND", "retained export not found"); return; }
            var workspace = found.get();
            if (path.length == 1) sendWorkspace(exchange, 200, workspace);
            else if (path.length == 2 && (path[1].equals("replay") || path[1].equals("validate"))) {
                handleBoundAction(exchange, workspace, boundRequest, path[1]);
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

    private static void handleBoundAction(HttpExchange exchange, DomainExportWorkspace workspace,
            Map<String, Object> boundRequest, String action) throws IOException {
        if (!workspace.contentHash().equals(boundRequest.get("expectedWorkspaceHash"))
                || !workspace.evidence().contentHash().equals(boundRequest.get("expectedEvidenceHash"))) {
            error(exchange, 409, "SOURCE_BINDING_MISMATCH", "request belongs to another source view"); return;
        }
        if (action.equals("validate")) {
            var receipt = DomainDownstreamValidation.validate(workspace);
            exchange.getResponseHeaders().set("X-Regelsuche-Run-Id", workspace.runId());
            exchange.getResponseHeaders().set("ETag", "\"" + receipt.contentHash().substring(7) + "\"");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"domain-validation-"
                + receipt.contentHash().substring(7) + ".json\"");
            send(exchange, 200, receipt.toCanonicalJson());
            return;
        }
        try {
            var replayed = workspace.replay();
            var json = new com.fasterxml.jackson.databind.json.JsonMapper();
            var result = json.createObjectNode().put("schema", DomainExportWorkspace.REPLAY_SCHEMA)
                .put("runId", workspace.runId()).put("workspaceContentHash", workspace.contentHash())
                .put("sourceEvidenceHash", workspace.evidence().contentHash()).put("status", "IDENTICAL_CANONICAL_EVIDENCE");
            result.set("evidence", json.readTree(replayed.toCanonicalJson()));
            exchange.getResponseHeaders().set("X-Regelsuche-Run-Id", workspace.runId());
            send(exchange, 200, json.writeValueAsString(result));
        } catch (IllegalStateException exception) { error(exchange, 409, "REPLAY_NOT_REPRODUCED", exception.getMessage()); }
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

    private static Map<String, Object> readBoundRequest(HttpExchange exchange, int maxBytes) throws IOException {
        requireJson(exchange);
        var request = new StreamingJsonRequestBody(Math.min(maxBytes, 4096)).readObject(exchange);
        if (!request.keySet().equals(Set.of("expectedWorkspaceHash", "expectedEvidenceHash"))
                || request.values().stream().anyMatch(value -> !(value instanceof String hash) || !hash.matches("sha256:[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("action requires exact workspace and source evidence identities");
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
