package de.regelsuche.web;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.CANDIDATE_DOSSIERS;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import de.regelsuche.discovery.representation.*;
import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.jar.JarFile;

/** Run-bound HTTP actions; routed and authenticated by the existing server. */
final class RetainedRunActions {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private RetainedRunActions() { }

    static void handle(HttpExchange exchange, Path directory, String suffix, int maxBytes) throws IOException {
        String[] path = suffix.split("/", -1);
        if (path.length != 2 || !path[0].matches("[0-9a-f]{64}")) {
            error(exchange, 400, "INVALID_RUN_DIGEST", "run path must contain a lowercase SHA-256 digest");
            return;
        }
        var found = RepresentationDiscoveryRunWorkspace.findRetained(directory, "sha256:" + path[0]);
        if (found.isEmpty()) { error(exchange, 404, "RUN_NOT_FOUND", "representation-discovery run not found"); return; }
        var run = found.get();
        try {
            byte[] requestBody = exchange.getRequestMethod().equals("POST") ? read(exchange, maxBytes) : null;
            if (path[1].equals("duplicate")) {
                duplicate(exchange, directory, run, requestBody);
            } else {
                var reference = run.artifacts().stream().filter(a -> a.role() == CANDIDATE_DOSSIERS).findFirst().orElseThrow();
                if (reference.status() != RepresentationDiscoveryArtifactReference.ArtifactStatus.AVAILABLE) {
                    error(exchange, 409, "DOSSIER_UNAVAILABLE", reference.status() + ": " + reference.detail()); return;
                }
                boolean nativeExecution = TargetFreeSearchExecution.SCHEMA.equals(reference.artifactSchema());
                if (!nativeExecution && !TargetFreeSymPyBridgeDiscoveryScenario.SCHEMA.equals(reference.artifactSchema())) {
                    error(exchange, 409, "UNSUPPORTED_DOSSIER", reference.artifactSchema()); return;
                }
                String body;
                boolean importing = exchange.getRequestMethod().equals("POST");
                if (importing) {
                    body = nativeExecution ? TargetFreeSearchArtifactStore.retain(directory, run, requestBody)
                        : RetainedCandidateDossierRepository.retain(directory, run, requestBody);
                } else {
                    var retained = nativeExecution ? TargetFreeSearchArtifactStore.read(directory, run)
                        : RetainedCandidateDossierRepository.read(directory, run);
                    if (retained.isEmpty()) { error(exchange, 404, "DOSSIER_NOT_RETAINED", "import the artifact bound by this manifest"); return; }
                    body = retained.get();
                }
                exchange.getResponseHeaders().set("X-Regelsuche-Run-Id", run.runId());
                exchange.getResponseHeaders().set("ETag", "\"" + reference.targetContentHash().substring(7) + "\"");
                send(exchange, importing ? 201 : 200, body);
            }
        } catch (IllegalArgumentException exception) {
            error(exchange, 400, path[1].equals("duplicate") ? "INVALID_RUN_CHANGE" : "INVALID_DOSSIER", exception.getMessage());
        }
    }

    static String nativeRevision() {
        String configured = System.getProperty("regelsuche.repository.revision", System.getenv("REGELSUCHE_REPOSITORY_REVISION"));
        String resource = "META-INF/regelsuche/source-revision";
        try {
            Path origin = Path.of(RetainedRunActions.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String embedded;
            if (Files.isDirectory(origin)) {
                // Gradle uses separate class and resource roots for an exploded application.
                try (var input = RetainedRunActions.class.getResourceAsStream("/" + resource)) {
                    embedded = input == null ? "" : new String(input.readNBytes(64), StandardCharsets.US_ASCII).strip();
                }
            } else {
                try (var file = new JarFile(origin.toFile())) {
                    var entry = file.getJarEntry(resource);
                    if (entry == null) embedded = "";
                    else try (var input = file.getInputStream(entry)) {
                        embedded = new String(input.readNBytes(64), StandardCharsets.US_ASCII).strip();
                    }
                }
            }
            if (!embedded.isEmpty() && !embedded.matches("[0-9a-f]{40}")) throw new IllegalStateException("invalid embedded source revision");
            if (configured == null || configured.isBlank()) return embedded;
            if (!embedded.isEmpty() && !embedded.equals(configured)) throw new IllegalStateException("configured revision differs from application");
            return configured;
        } catch (IOException | URISyntaxException unavailable) {
            throw new IllegalStateException("application source revision unavailable", unavailable);
        }
    }

    static void handleOperation(HttpExchange exchange, NativeSearchOperations operations, String suffix, int maxBytes) throws IOException {
        String[] parts = suffix.split("/", -1);
        try {
            if (parts.length == 1 && exchange.getRequestMethod().equals("GET")) {
                send(exchange, 200, JSON.writeValueAsString(operations.ids()));
            } else if (parts.length == 1) {
                var operation = start(operations, readJson(exchange, Math.min(4096, maxBytes)));
                exchange.getResponseHeaders().set("Location", "/api/discovery-runs/operations/" + operation.operationId());
                send(exchange, 202, NativeSearchOperationStore.encode(operation));
            } else {
                var operation = parts.length == 2 ? operations.find(parts[1]).orElseThrow() : operations.control(parts[1], parts[2]);
                send(exchange, 200, NativeSearchOperationStore.encode(operation));
            }
        } catch (NoSuchElementException missing) {
            error(exchange, 404, "OPERATION_NOT_FOUND", "operation not found");
        } catch (IllegalArgumentException invalid) {
            error(exchange, 400, "INVALID_OPERATION", invalid.getMessage());
        } catch (IllegalStateException busy) {
            error(exchange, 409, "OPERATION_UNAVAILABLE", busy.getMessage());
        } catch (BoundedRequestBody.PayloadTooLargeException oversized) {
            throw oversized;
        } catch (IOException unavailable) {
            error(exchange, 500, "OPERATION_STORAGE_FAILURE", "could not read or retain the operation");
        }
    }

    private static NativeSearchOperation start(NativeSearchOperations operations, Map<String, Object> request) throws IOException {
        if (!Set.of("source", "profile", "startPaused").containsAll(request.keySet()) || !(request.get("source") instanceof String source)
                || !(request.get("profile") instanceof String profile) || !(request.getOrDefault("startPaused", false) instanceof Boolean paused)) {
            throw new IllegalArgumentException("expected source, profile and optional boolean startPaused");
        }
        return operations.start(source, profile, paused);
    }

    static Map<String, Object> readJson(HttpExchange exchange, int maxBytes) throws IOException {
        String media = exchange.getRequestHeaders().getFirst("Content-Type");
        if (media == null || !media.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) throw new IllegalArgumentException("expected JSON");
        try {
            return new StreamingJsonRequestBody(maxBytes).readObject(exchange);
        } catch (StreamingJsonRequestBody.MalformedJsonRequestException malformed) {
            throw new IllegalArgumentException("invalid JSON request body", malformed);
        }
    }

    private static void duplicate(HttpExchange exchange, Path directory, RepresentationDiscoveryRunWorkspace parent, byte[] requestBody) throws IOException {
        com.fasterxml.jackson.databind.JsonNode request;
        try { request = JSON.readTree(requestBody); }
        catch (com.fasterxml.jackson.core.JacksonException exception) { throw new IllegalArgumentException("invalid seed change JSON", exception); }
        if (request == null || !request.isObject() || request.size() != 1 || !request.has("deterministicSeed")) {
            throw new IllegalArgumentException("exactly one deterministicSeed parameter is required");
        }
        var value = request.get("deterministicSeed");
        if ((!value.isTextual() && !value.isIntegralNumber()) || !value.asText().matches("-?(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException("deterministicSeed must be an exact Java long");
        }
        long seed = Long.parseLong(value.asText());
        var plan = parent.plan();
        var changed = RepresentationDiscoveryRunPlan.create(plan.informationTrack(), plan.informationBoundaryHash(),
            plan.ruleInventoryHash(), plan.knowledgePackSelectionHash(), plan.knownStructureCatalogHash(), plan.searchStrategyId(),
            plan.searchProfileId(), plan.objectiveId(), plan.budgetHash(), seed, plan.backendIdentities());
        var duplicate = RepresentationDiscoveryRunWorkspace.duplicateWithOnePlanChange(parent, changed, parent.revisions());
        RepresentationDiscoveryRunWorkspace.retain(directory, duplicate);
        exchange.getResponseHeaders().set("Location", "/api/discovery-runs/" + duplicate.runId().substring(7));
        exchange.getResponseHeaders().set("ETag", "\"" + duplicate.runId().substring(7) + "\"");
        send(exchange, 201, duplicate.toCanonicalJson());
    }

    private static byte[] read(HttpExchange exchange, int maxBytes) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim().equals("application/json")) {
            throw new IllegalArgumentException("Content-Type must be application/json");
        }
        try (var body = BoundedRequestBody.open(exchange, Math.min(maxBytes, RetainedCandidateDossierRepository.MAX_BYTES))) {
            return body.readAllBytes();
        }
    }

    static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        send(exchange, status, new JsonWriter().beginObject().property("error", true).property("code", code)
            .property("message", message == null ? code : message).endObject().toString());
    }

    static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var body = exchange.getResponseBody()) { body.write(bytes); }
    }
}
