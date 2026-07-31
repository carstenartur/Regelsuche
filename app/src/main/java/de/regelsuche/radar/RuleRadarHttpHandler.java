package de.regelsuche.radar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.inventory.RuleInventoryRepository;
import de.regelsuche.json.JsonReader;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.plugin.PluginRuntimeConfig;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.web.BoundedRequestBody;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private final int maxRequestBytes;

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
        this.maxRequestBytes = maxRequestBytes;
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
        Map<String, Object> body = readObject(exchange);
        String expression = string(body, "expression", "");
        Context context = context(body);
        Snapshot snapshot = radar.inspect(expression, context);
        // Invalid expressions are a normal, structured inspection result rather
        // than an uncaught server error.
        sendJson(exchange, 200, RuleRadarJson.snapshot(snapshot));
    }

    private void handleApply(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "POST required");
            return;
        }
        Map<String, Object> body = readObject(exchange);
        String expression = string(body, "expression", "");
        String candidateId = string(body, "candidateId", "");
        if (expression.isBlank() || candidateId.isBlank()) {
            sendError(exchange, 400, "MISSING_FIELD", "expression and candidateId are required");
            return;
        }
        Context context = context(body);
        ApplicableMove candidate = radar.resolve(expression, candidateId, context).orElse(null);
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
        Map<String, Object> body = readObject(exchange);
        RuleRadarSearchService.SearchRequest request = new RuleRadarSearchService.SearchRequest(
            string(body, "expression", ""),
            string(body, "targetExpression", ""),
            context(body),
            integer(body, "maxDepth", 4),
            integer(body, "maxStates", 120),
            integer(body, "maxMovesPerState", 60)
        );
        sendJson(exchange, 200, RuleRadarJson.searchResult(search.search(request)));
    }

    private Context context(Map<String, Object> body) {
        Map<String, Object> source = map(body.get("context"));
        if (source.isEmpty()) {
            source = body;
        }
        RuleProfile knowledgeProfile = enumValue(
            RuleProfile.class,
            string(source, "knowledgeProfile", RuleProfile.CORE.name()),
            RuleProfile.CORE
        );
        CandidateProofStatus minStatus = enumValue(
            CandidateProofStatus.class,
            string(source, "minMacroProofStatus", CandidateProofStatus.VALIDATED_BY_EXAMPLES.name()),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES
        );
        Map<String, CandidateOutcome> outcomes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map(source.get("outcomeByCandidateId")).entrySet()) {
            CandidateOutcome parsed = enumValue(CandidateOutcome.class, String.valueOf(entry.getValue()), null);
            if (parsed != null && entry.getKey() != null && !entry.getKey().isBlank()) {
                outcomes.put(entry.getKey(), parsed);
            }
        }
        return new Context(
            knowledgeProfile,
            stringSet(source.get("enabledPacks")),
            stringSet(source.get("disabledPacks")),
            bool(source, "includePlugins", true),
            bool(source, "includeLearnedMacros", true),
            minStatus,
            string(source, "searchProfile", "DISCOVERY"),
            string(source, "goalExpression", ""),
            integer(source, "maxCandidatesPerPosition", 24),
            integer(source, "maxCandidatesTotal", 240),
            stringList(source.get("assumptions")),
            bool(source, "includeRejectedCandidates", true),
            string(source, "selectedCandidateId", ""),
            outcomes
        );
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

    private Map<String, Object> readObject(HttpExchange exchange) throws IOException {
        byte[] bytes = BoundedRequestBody.read(exchange, maxRequestBytes);
        if (bytes.length == 0) {
            return Map.of();
        }
        return new JsonReader(new String(bytes, StandardCharsets.UTF_8)).readObject();
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

    private String string(Map<String, Object> source, String key, String fallback) {
        Object value = source.get(key);
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private int integer(Map<String, Object> source, String key, int fallback) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private boolean bool(Map<String, Object> source, String key, boolean fallback) {
        Object value = source.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> fallback;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(item -> item != null && !String.valueOf(item).isBlank())
            .map(item -> String.valueOf(item).trim())
            .distinct()
            .sorted()
            .toList();
    }

    private Set<String> stringSet(Object value) {
        return Set.copyOf(new LinkedHashSet<>(stringList(value)));
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            return fallback;
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
