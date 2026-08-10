package de.regelsuche.radar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.inventory.RuleInventoryRepository;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.plugin.PluginRuntimeConfig;
import de.regelsuche.web.BoundedRequestBody;
import de.regelsuche.web.StreamingJsonRequestBody;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static de.regelsuche.radar.AstRuleRadar.ApplicableMove;
import static de.regelsuche.radar.AstRuleRadar.CandidateOutcome;
import static de.regelsuche.radar.AstRuleRadar.Context;
import static de.regelsuche.radar.AstRuleRadar.Snapshot;

/** HTTP adapter for the AST rule radar. Mathematical logic remains in the service. */
public final class RuleRadarHttpHandler implements HttpHandler, AutoCloseable {
    private static final String PREFIX = "/api/rule-radar";
    private static final int DEFAULT_MAX_REQUEST_BYTES = 1 << 20;
    private final AstRuleRadarService radar;
    private final RuleRadarSearchService search;
    private final StreamingJsonRequestBody jsonRequestBody;

    public RuleRadarHttpHandler(
        RuleInventoryRepository inventory,
        ExpressionGraphStore graphStore,
        PluginRuntimeConfig pluginRuntimeConfig
    ) {
        this(inventory, graphStore, pluginRuntimeConfig, DEFAULT_MAX_REQUEST_BYTES);
    }

    public RuleRadarHttpHandler(
        RuleInventoryRepository inventory,
        ExpressionGraphStore graphStore,
        PluginRuntimeConfig pluginRuntimeConfig,
        int maxRequestBytes
    ) {
        if (maxRequestBytes <= 0) {
            throw new IllegalArgumentException("maxRequestBytes must be positive");
        }
        this.radar = new AstRuleRadarService(inventory, graphStore, pluginRuntimeConfig);
        this.search = new RuleRadarSearchService(radar);
        this.jsonRequestBody = new StreamingJsonRequestBody(maxRequestBytes);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String suffix = path.length() <= PREFIX.length() ? "" : path.substring(PREFIX.length());
        try {
            switch (suffix) {
                case "/inspect" -> handleInspect(exchange);
                case "/apply" -> handleApply(exchange);
                case "/search" -> handleSearch(exchange);
                case "", "/" -> sendJson(exchange, 200, capabilityDocument());
                default -> sendError(exchange, 404, "NOT_FOUND", "unknown rule-radar endpoint");
            }
        } catch (BoundedRequestBody.PayloadTooLargeException exception) {
            sendPayloadTooLarge(exchange, exception.limitBytes());
        } catch (StreamingJsonRequestBody.MalformedJsonRequestException exception) {
            sendError(exchange, 400, "INVALID_JSON", "invalid JSON request body");
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, "INVALID_REQUEST", safeMessage(exception));
        } catch (RuntimeException exception) {
            sendError(exchange, 500, "RADAR_FAILURE", safeMessage(exception));
        }
    }

    private void handleInspect(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "POST required");
            return;
        }
        RuleRadarRequestBody body = readRequest(exchange);
        Snapshot snapshot = radar.inspect(body.expression(), body.context());
        // Invalid expressions are a normal, structured inspection result rather
        // than an uncaught server error.
        sendJson(exchange, 200, RuleRadarJson.snapshot(snapshot));
    }

    private void handleApply(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "POST required");
            return;
        }
        RuleRadarRequestBody body = readRequest(exchange);
        if (body.expression().isBlank() || body.candidateId().isBlank()) {
            sendError(exchange, 400, "MISSING_FIELD", "expression and candidateId are required");
            return;
        }
        Context context = body.context();
        ApplicableMove candidate = radar.resolve(
            body.expression(), body.candidateId(), context).orElse(null);
        if (candidate == null) {
            sendError(exchange, 409, "STALE_CANDIDATE",
                "candidate is no longer present for the frozen expression and context");
            return;
        }
        if (!candidate.applicable()) {
            sendError(exchange, 422, candidate.outcome().name(),
                "candidate is visible for audit but is not executable in the current context");
            return;
        }
        Snapshot refreshed = radar.inspect(candidate.expressionAfter(), withoutSelectionAndOutcomes(context));
        sendJson(exchange, 200, RuleRadarJson.applyResult(
            candidate,
            refreshed,
            !candidate.expressionBefore().equals(candidate.expressionAfter())
        ));
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "POST required");
            return;
        }
        RuleRadarRequestBody body = readRequest(exchange);
        RuleRadarSearchService.SearchRequest request = new RuleRadarSearchService.SearchRequest(
            body.expression(),
            body.targetExpression(),
            body.context(),
            body.maxDepth(),
            body.maxStates(),
            body.maxMovesPerState()
        );
        sendJson(exchange, 200, RuleRadarJson.searchResult(search.search(request)));
    }

    private Context withoutSelectionAndOutcomes(Context context) {
        return new Context(
            context.knowledgeProfile(),
            context.enabledPacks(),
            context.disabledPacks(),
            context.includePlugins(),
            context.includeLearnedMacros(),
            context.minMacroProofStatus(),
            context.searchProfile(),
            context.goalExpression(),
            context.maxCandidatesPerPosition(),
            context.maxCandidatesTotal(),
            context.assumptions(),
            context.includeRejectedCandidates(),
            "",
            Map.of()
        );
    }

    private String capabilityDocument() {
        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("schema", "regelsuche.ast-rule-radar-http/v1");
        writer.stringArray("endpoints", List.of(
            "POST /api/rule-radar/inspect",
            "POST /api/rule-radar/apply",
            "POST /api/rule-radar/search"
        ));
        writer.stringArray("candidateOutcomes", java.util.Arrays.stream(CandidateOutcome.values())
            .map(Enum::name).toList());
        writer.stringArray("ruleOrigins", java.util.Arrays.stream(AstRuleRadar.RuleOrigin.values())
            .map(Enum::name).toList());
        writer.property("claimBoundary",
            "Candidate visibility and rewrite success are not formal proof; validation and proof metadata remain authoritative.");
        return writer.endObject().toString();
    }

    private RuleRadarRequestBody readRequest(HttpExchange exchange)
            throws IOException {
        return RuleRadarRequestBody.read(jsonRequestBody, exchange);
    }

    private void sendPayloadTooLarge(HttpExchange exchange, int limitBytes) throws IOException {
        JsonWriter writer = new JsonWriter().beginObject()
            .property("error", true)
            .property("code", "PAYLOAD_TOO_LARGE")
            .property("message", "request body exceeds configured limit")
            .property("limitBytes", limitBytes)
            .endObject();
        sendJson(exchange, 413, writer.toString());
    }

    private void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        JsonWriter writer = new JsonWriter().beginObject()
            .property("error", true)
            .property("code", code)
            .property("message", message)
            .endObject();
        sendJson(exchange, status, writer.toString());
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    @Override
    public void close() {
        radar.close();
    }
}
