package de.regelsuche.web;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.CANDIDATE_DOSSIERS;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactStatus.AVAILABLE;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunWorkspace;
import de.regelsuche.discovery.representation.TargetFreeSearchArtifactStore;
import de.regelsuche.discovery.representation.TargetFreeSearchExecution;
import de.regelsuche.discovery.representation.TargetFreeSymPyBridgeDiscoveryScenario;
import de.regelsuche.literature.CandidateLiteratureAssessments;
import de.regelsuche.literature.LiteratureReviewRepository;
import de.regelsuche.literature.LiteratureReviewRepository.Subject;
import de.regelsuche.literature.TheoremSearchClient;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit operator actions on private literature evidence for an immutable retained candidate. */
final class LiteratureWorkbenchActions {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CONSENT_HEADER = "X-Regelsuche-Literature";
    private static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "::1", "[::1]", "localhost");
    private final Path runs;
    private final boolean privateAccess;
    private final boolean requireLoopbackAuthority;
    private final int requestLimit;
    private final LiteratureReviewRepository receipts;
    private final CandidateLiteratureAssessments assessments;
    private final TheoremSearchClient client;
    private final AtomicBoolean lookupInProgress = new AtomicBoolean();

    LiteratureWorkbenchActions(Path runs, String host, WebSecurityConfig security) {
        this.runs = runs;
        requireLoopbackAuthority = !security.isAuthEnabled();
        privateAccess = !requireLoopbackAuthority || LOCAL_HOSTS.contains(host);
        requestLimit = security.maxRequestBytes();
        receipts = new LiteratureReviewRepository(runs.resolveSibling(runs.getFileName() + "-literature"));
        assessments = new CandidateLiteratureAssessments(runs.resolveSibling(runs.getFileName() + "-literature-assessments"));
        boolean optIn = Boolean.parseBoolean(System.getProperty("regelsuche.literature.theoremsearch.enabled",
            System.getenv("REGELSUCHE_THEOREMSEARCH_ENABLED")));
        client = new TheoremSearchClient(privateAccess && optIn);
    }

    void handle(HttpExchange exchange, String suffix) throws IOException {
        if (!privateAccess || !sameOriginAction(exchange)) {
            error(exchange, 403, "PRIVATE_WORKBENCH_REQUIRED");
            return;
        }
        String method = exchange.getRequestMethod();
        try {
            Map<String, Object> query = Map.of();
            byte[] assessment = null;
            if ("POST".equals(method)) {
                requireJson(exchange);
                query = new StreamingJsonRequestBody(Math.min(requestLimit, 4096)).readObject(exchange);
                if (!client.enabled()) {
                    error(exchange, 403, "THEOREMSEARCH_DISABLED");
                    return;
                }
            } else if ("PUT".equals(method)) {
                requireJson(exchange);
                try (var input = BoundedRequestBody.open(exchange, Math.min(requestLimit, CandidateLiteratureAssessments.MAX_BYTES))) {
                    assessment = input.readAllBytes();
                }
            }
            String[] parts = suffix.split("/", -1);
            if (parts.length != 3 || !parts[0].matches("[0-9a-f]{64}") || !parts[2].matches("[0-9a-f]{64}")) {
                error(exchange, 400, "INVALID_LITERATURE_SUBJECT");
                return;
            }
            var subject = retainedSubject("sha256:" + parts[0], "sha256:" + parts[2]);
            if (subject.isEmpty()) {
                error(exchange, 404, "LITERATURE_CANDIDATE_NOT_RETAINED");
                return;
            }
            switch (method) {
                case "GET" -> read(exchange, subject.get());
                case "POST" -> lookup(exchange, subject.get(), query);
                case "PUT" -> send(exchange, 201, JSON.valueToTree(assessments.retain(subject.get(), assessment)));
                default -> error(exchange, 405, "METHOD_NOT_ALLOWED");
            }
        } catch (BoundedRequestBody.PayloadTooLargeException exception) {
            throw exception;
        } catch (CandidateLiteratureAssessments.InvalidAssessmentException
                | StreamingJsonRequestBody.MalformedJsonRequestException | IllegalArgumentException exception) {
            error(exchange, 400, "INVALID_LITERATURE_REQUEST");
        } catch (IOException | IllegalStateException exception) {
            error(exchange, 500, "PRIVATE_LITERATURE_STORE_UNAVAILABLE");
        }
    }

    private Optional<Subject> retainedSubject(String runId, String candidateId) throws IOException {
        var retained = RepresentationDiscoveryRunWorkspace.findRetained(runs, runId);
        if (retained.isEmpty()) return Optional.empty();
        var run = retained.get();
        var reference = run.artifacts().stream().filter(item -> item.role() == CANDIDATE_DOSSIERS).findFirst().orElseThrow();
        if (reference.status() != AVAILABLE) return Optional.empty();
        boolean matches = switch (reference.artifactSchema()) {
            case TargetFreeSearchExecution.SCHEMA -> {
                var artifact = TargetFreeSearchArtifactStore.read(runs, run);
                if (artifact.isEmpty()) yield false;
                var states = JSON.readTree(artifact.get()).path("content").path("states");
                boolean found = false;
                for (var state : states) {
                    if (candidateId.equals(state.path("stateId").asText()) && !state.path("generationSequences").isEmpty()) {
                        found = true;
                        break;
                    }
                }
                yield found;
            }
            case TargetFreeSymPyBridgeDiscoveryScenario.SCHEMA -> {
                var artifact = RetainedCandidateDossierRepository.read(runs, run);
                yield artifact.isPresent() && candidateId.equals(JSON.readTree(artifact.get())
                    .path("content").path("discoveredBridge").path("stateHash").asText());
            }
            default -> false;
        };
        return matches ? Optional.of(new Subject(runId, candidateId, reference.targetContentHash())) : Optional.empty();
    }

    private void read(HttpExchange exchange, Subject subject) throws IOException {
        var response = JSON.createObjectNode().put("onlineEnabled", client.enabled())
            .put("endpoint", TheoremSearchClient.ENDPOINT.toString())
            .put("maxQueryChars", TheoremSearchClient.MAX_QUERY_CHARS)
            .put("resultLimit", TheoremSearchClient.RESULT_LIMIT);
        response.set("subject", JSON.valueToTree(subject));
        response.set("receipts", JSON.valueToTree(receipts.list(subject)));
        response.set("assessments", JSON.valueToTree(assessments.list(subject)));
        send(exchange, 200, response);
    }

    private void lookup(HttpExchange exchange, Subject subject, Map<String, Object> body) throws IOException {
        if (!body.keySet().equals(Set.of("query", "confirmed")) || !(body.get("query") instanceof String query)) {
            error(exchange, 400, "INVALID_LITERATURE_QUERY");
            return;
        }
        if (!Boolean.TRUE.equals(body.get("confirmed"))) {
            error(exchange, 403, "LITERATURE_CONFIRMATION_REQUIRED");
            return;
        }
        if (!lookupInProgress.compareAndSet(false, true)) {
            exchange.getResponseHeaders().set("Retry-After", "1");
            error(exchange, 429, "LITERATURE_LOOKUP_IN_PROGRESS");
            return;
        }
        try {
            var pending = receipts.begin(subject, query);
            Thread.startVirtualThread(() -> finishLookup(exchange, pending));
        } catch (IOException | RuntimeException exception) {
            lookupInProgress.set(false);
            throw exception;
        }
    }

    private void finishLookup(HttpExchange exchange, LiteratureReviewRepository.Receipt pending) {
        try {
            var result = client.search(pending.query(), true);
            var receipt = receipts.complete(pending, result);
            int status = switch (result.status()) {
                case SUCCESS -> 201;
                case RATE_LIMITED -> 429;
                default -> 502;
            };
            if (status == 429) exchange.getResponseHeaders().set("Retry-After", Long.toString(result.retryAfterSeconds()));
            send(exchange, status, JSON.valueToTree(receipt));
        } catch (IOException | RuntimeException exception) {
            try {
                error(exchange, 500, "PRIVATE_LITERATURE_LOOKUP_UNAVAILABLE");
            } catch (IOException ignored) {
                exchange.close();
            }
        } finally {
            lookupInProgress.set(false);
        }
    }

    private boolean sameOriginAction(HttpExchange exchange) {
        var headers = exchange.getRequestHeaders();
        String fetchSite = headers.getFirst("Sec-Fetch-Site");
        String authority = headers.getFirst("Host");
        if (authority == null || !"workbench".equals(headers.getFirst(CONSENT_HEADER))
                || (fetchSite != null && !"same-origin".equals(fetchSite))) return false;
        try {
            var host = URI.create("http://" + authority);
            if (host.getHost() == null || !authority.equals(host.getRawAuthority()) || host.getRawUserInfo() != null
                    || (requireLoopbackAuthority && !LOCAL_HOSTS.contains(host.getHost().toLowerCase(Locale.ROOT)))) {
                return false;
            }
            String origin = headers.getFirst("Origin");
            if (origin == null) return true;
            var uri = URI.create(origin);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && authority.equalsIgnoreCase(uri.getRawAuthority());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void requireJson(HttpExchange exchange) {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !"application/json".equals(type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("expected JSON");
        }
    }

    private static void error(HttpExchange exchange, int status, String code) throws IOException {
        send(exchange, status, JSON.createObjectNode().put("error", true).put("code", code).put("message", code));
    }

    private static void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
