package de.regelsuche.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.regelsuche.api.ExportQueryService;
import de.regelsuche.api.RuleInventoryQueryService;
import de.regelsuche.api.TransformationQueryService;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.example.AlgebraicExampleGenerator;
import de.regelsuche.explain.ExplanationService;
import de.regelsuche.export.DefaultTransformationImportService;
import de.regelsuche.export.ExportBundle;
import de.regelsuche.export.TransformationExportService;
import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.inventory.RuleInventoryRepository;
import de.regelsuche.json.JsonReader;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.DiscoverySettings;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleCandidateListener;
import de.regelsuche.mining.RuleCandidateMiner;
import de.regelsuche.mining.RuleDiscoveryService;
import de.regelsuche.notify.NoOpNotifier;
import de.regelsuche.notify.SimplificationNotifier;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchProfile;
import de.regelsuche.search.TransformationSearchService;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.SymPyTransformationEngine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal embedded web workbench backed by the JDK's built-in
 * {@link HttpServer}. Implements the bare-bones REST surface from the issue
 * (search, graph view, paths, rule candidates, inventory, exports) and serves
 * a small static HTML/JS UI bundled as a classpath resource.
 *
 * <p>This intentionally avoids any heavy web framework dependency. It is
 * sufficient for local exploration of discovered transformations.</p>
 */
public class WebWorkbenchServer {
    private final String host;
    private final int port;
    private final ExpressionGraphStore graphStore;
    private final RuleInventoryRepository inventoryRepository;
    private final TransformationExportService exportService;

    private final TransformationQueryService transformationQuery;
    private final RuleInventoryQueryService inventoryQuery;
    private final ExportQueryService exportQuery;
    private final ExplanationService explanationService = new ExplanationService();

    private HttpServer server;

    public WebWorkbenchServer(
        String host,
        int port,
        ExpressionGraphStore graphStore,
        RuleInventoryRepository inventoryRepository,
        TransformationExportService exportService
    ) {
        this.host = host;
        this.port = port;
        this.graphStore = graphStore;
        this.inventoryRepository = inventoryRepository;
        this.exportService = exportService;
        this.transformationQuery = new TransformationQueryService(graphStore);
        this.inventoryQuery = new RuleInventoryQueryService(graphStore, inventoryRepository);
        this.exportQuery = new ExportQueryService(graphStore, inventoryRepository);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/search", this::handleSearch);
        server.createContext("/api/discover", this::handleDiscover);
        server.createContext("/api/paths", this::handlePaths);
        server.createContext("/api/graph", this::handleGraph);
        server.createContext("/api/candidates", this::handleCandidates);
        server.createContext("/api/inventory", this::handleInventory);
        server.createContext("/api/exports", this::handleExports);
        server.createContext("/api/explain", this::handleExplain);
        server.createContext("/", this::handleStatic);
        server.setExecutor(null);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int boundPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendStatus(exchange, 405, "method not allowed");
            return;
        }
        Map<String, Object> body = readJsonObject(exchange);
        String expression = stringValue(body, "expression", "");
        String typeName = stringValue(body, "type", InputType.TERM.name()).toUpperCase(Locale.ROOT);
        String profileName = stringValue(body, "profile", SearchProfile.FAST_SIMPLIFY.name()).toUpperCase(Locale.ROOT);
        if (expression.isBlank()) {
            sendStatus(exchange, 400, "expression must not be blank");
            return;
        }
        InputType type;
        SearchProfile profile;
        try {
            type = InputType.valueOf(typeName);
            profile = SearchProfile.valueOf(profileName);
        } catch (IllegalArgumentException ex) {
            sendStatus(exchange, 400, ex.getMessage());
            return;
        }
        TransformationSearchService search = new TransformationSearchService(
            new SymPyTransformationEngine(),
            graphStore,
            profile.heuristic(),
            new NoOpNotifier(),
            profile.newStrategy()
        );
        try {
            search.submit(new InputRequest(type, expression)).join();
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writer.property("profile", profile.name());
            writer.property("inputType", type.name());
            writer.property("expression", expression);
            writer.property("successes", search.getSuccesses().size());
            search.getBestSolution().ifPresentOrElse(
                best -> {
                    writer.property("bestExpression", best.simplifiedExpression());
                    writer.property("improvement", best.improvement());
                },
                () -> writer.nullProperty("bestExpression")
            );
            writer.endObject();
            sendJson(exchange, 200, writer.toString());
        } finally {
            search.shutdown();
        }
    }

    private void handleDiscover(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendStatus(exchange, 405, "method not allowed");
            return;
        }
        Map<String, Object> body = readJsonObject(exchange);
        int min = intValue(body, "min", 1);
        int max = intValue(body, "max", 3);
        RuleDiscoveryService discovery = new RuleDiscoveryService(
            new AlgebraicExampleGenerator(),
            new AstRewriteTransformationEngine(),
            new SymPyEquivalenceService(),
            new ExpressionScorer(),
            graphStore,
            new RuleCandidateMiner(new KnownRuleRepository()),
            RuleCandidateListener.NOOP,
            new de.regelsuche.search.strategy.BestFirstSearchStrategy(),
            DiscoverySettings.collectingEquivalentPaths()
        );
        try {
            List<RuleCandidate> candidates = discovery.discover(min, max);
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writer.property("ruleCandidates", candidates.size());
            writer.property("transformations", graphStore.discoveredTransformations().size());
            writer.endObject();
            sendJson(exchange, 200, writer.toString());
        } finally {
            discovery.shutdown();
        }
    }

    private void handlePaths(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring("/api/paths".length());
        if (suffix.startsWith("/")) {
            suffix = suffix.substring(1);
        }
        if (suffix.isEmpty()) {
            sendJson(exchange, 200, dtoListToJson("transformations", transformationQuery.bestImprovements()));
            return;
        }
        transformationQuery.pathById(suffix).ifPresentOrElse(
            dto -> {
                try {
                    sendJson(exchange, 200, singleDtoJson(dto));
                } catch (IOException ex) {
                    // already logged by JDK HttpServer
                }
            },
            () -> {
                try {
                    sendStatus(exchange, 404, "path not found");
                } catch (IOException ignored) {
                }
            }
        );
    }

    private void handleGraph(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring("/api/graph".length()).replaceFirst("^/", "");
        if (suffix.isEmpty()) {
            sendText(exchange, 200, exportService.exportMermaid(graphStore.discoveredTransformations()));
        } else {
            List<DiscoveredTransformation> filtered = graphStore.discoveredTransformations().stream()
                .filter(t -> t.id().equals(suffix))
                .toList();
            sendText(exchange, 200, exportService.exportMermaid(filtered));
        }
    }

    private void handleCandidates(HttpExchange exchange) throws IOException {
        StringBuilder json = new StringBuilder("{\"candidates\":[");
        boolean first = true;
        for (var dto : inventoryQuery.ruleCandidateDtos()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writer.property("leftPattern", dto.leftPattern());
            writer.property("rightPattern", dto.rightPattern());
            writer.property("examplesCount", dto.examplesCount());
            writer.property("averageScoreImprovement", dto.averageScoreImprovement());
            writer.property("maximumScoreImprovement", dto.maximumScoreImprovement());
            writer.property("equivalenceVerified", dto.equivalenceVerified());
            writer.property("status", dto.status());
            writer.property("proofStatus", dto.proofStatus());
            writer.endObject();
            json.append(writer);
        }
        json.append("]}");
        sendJson(exchange, 200, json.toString());
    }

    private void handleInventory(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            StringBuilder json = new StringBuilder("{\"rules\":[");
            boolean first = true;
            for (var dto : inventoryQuery.reusableRules()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                JsonWriter writer = new JsonWriter();
                writer.beginObject();
                writer.property("id", dto.id());
                writer.property("leftPattern", dto.leftPattern());
                writer.property("rightPattern", dto.rightPattern());
                writer.property("proofStatus", dto.proofStatus());
                writer.property("enabled", inventoryRepository.isEnabled(dto.id()));
                writer.stringArray("tags", List.copyOf(inventoryRepository.tagsOf(dto.id())));
                writer.endObject();
                json.append(writer);
            }
            json.append("]}");
            sendJson(exchange, 200, json.toString());
            return;
        }
        if ("POST".equalsIgnoreCase(method)) {
            // POST /api/inventory/import with JSON body { "json": "..." } imports a bundle.
            Map<String, Object> body = readJsonObject(exchange);
            String bundleJson = stringValue(body, "json", "");
            if (bundleJson.isBlank()) {
                sendStatus(exchange, 400, "missing 'json' field");
                return;
            }
            ExportBundle bundle = new DefaultTransformationImportService().importJson(bundleJson);
            inventoryRepository.importBundle(bundle);
            sendJson(exchange, 200, "{\"imported\":" + bundle.reusableRules().size() + "}");
            return;
        }
        sendStatus(exchange, 405, "method not allowed");
    }

    private void handleExports(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String format = path.substring("/api/exports".length()).replaceFirst("^/", "");
        if (format.isEmpty()) {
            sendJson(exchange, 200, exportService.exportBundle(exportQuery.bundle()));
            return;
        }
        List<DiscoveredTransformation> transformations = graphStore.discoveredTransformations();
        switch (format.toLowerCase(Locale.ROOT)) {
            case "markdown", "md" -> sendText(exchange, 200, exportService.exportMarkdown(transformations));
            case "latex", "tex" -> sendText(exchange, 200, exportService.exportLatex(transformations));
            case "mermaid", "mmd" -> sendText(exchange, 200, exportService.exportMermaid(transformations));
            case "json" -> sendJson(exchange, 200, exportService.exportBundle(exportQuery.bundle()));
            default -> sendStatus(exchange, 404, "unknown format: " + format);
        }
    }

    private void handleExplain(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring("/api/explain".length()).replaceFirst("^/", "");
        if (suffix.isEmpty()) {
            sendStatus(exchange, 400, "expected /api/explain/{pathId}");
            return;
        }
        String form = queryParam(exchange, "form", "SCHOOL").toUpperCase(Locale.ROOT);
        ExplanationService.Form formEnum;
        try {
            formEnum = ExplanationService.Form.valueOf(form);
        } catch (IllegalArgumentException ex) {
            sendStatus(exchange, 400, "invalid form: " + form);
            return;
        }
        var match = graphStore.discoveredTransformations().stream()
            .filter(t -> t.id().equals(suffix))
            .findFirst();
        if (match.isEmpty()) {
            sendStatus(exchange, 404, "path not found");
            return;
        }
        if (formEnum == ExplanationService.Form.JSON) {
            sendJson(exchange, 200, explanationService.renderPath(match.get(), formEnum));
        } else {
            sendText(exchange, 200, explanationService.renderPath(match.get(), formEnum));
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path) || "/index.html".equals(path)) {
            sendStaticResource(exchange, "/web/index.html", "text/html; charset=utf-8");
        } else if (path.startsWith("/static/")) {
            String resource = "/web" + path.substring("/static".length());
            sendStaticResource(exchange, resource, mimeFor(resource));
        } else {
            sendStatus(exchange, 404, "not found");
        }
    }

    private String mimeFor(String resource) {
        if (resource.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (resource.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private void sendStaticResource(HttpExchange exchange, String resource, String contentType) throws IOException {
        try (InputStream stream = WebWorkbenchServer.class.getResourceAsStream(resource)) {
            if (stream == null) {
                sendStatus(exchange, 404, "missing resource: " + resource);
                return;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            stream.transferTo(buffer);
            byte[] bytes = buffer.toByteArray();
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        }
    }

    private String dtoListToJson(String wrapper, List<?> dtos) {
        // Reuse the existing export JSON shape for transformations.
        StringBuilder builder = new StringBuilder("{\"" + wrapper + "\":[");
        for (int i = 0; i < dtos.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(singleDtoJson(dtos.get(i)));
        }
        builder.append("]}");
        return builder.toString();
    }

    private String singleDtoJson(Object dto) {
        if (dto instanceof de.regelsuche.api.TransformationPathDto pathDto) {
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writer.property("id", pathDto.id());
            writer.property("originalExpression", pathDto.originalExpression());
            writer.property("improvedExpression", pathDto.improvedExpression());
            writer.property("originalScore", pathDto.originalScore());
            writer.property("improvedScore", pathDto.improvedScore());
            writer.property("totalImprovement", pathDto.totalImprovement());
            writer.property("validationStatus", pathDto.validationStatus());
            writer.property("discoveredAt", pathDto.discoveredAt() == null ? "" : pathDto.discoveredAt().toString());
            writer.array("steps", w -> pathDto.steps().forEach(step ->
                w.objectValue(inner -> {
                    inner.property("index", step.index());
                    inner.property("beforeExpression", step.beforeExpression());
                    inner.property("afterExpression", step.afterExpression());
                    inner.property("ruleId", step.ruleId());
                    inner.property("ruleKind", step.ruleKind());
                    inner.property("scoreBefore", step.scoreBefore());
                    inner.property("scoreAfter", step.scoreAfter());
                    inner.property("equivalencePreserving", step.equivalencePreserving());
                })));
            writer.endObject();
            return writer.toString();
        }
        return "{}";
    }

    private Map<String, Object> readJsonObject(HttpExchange exchange) throws IOException {
        try (InputStream stream = exchange.getRequestBody()) {
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return Map.of();
            }
            return new JsonReader(text).readObject();
        }
    }

    private String stringValue(Map<String, Object> body, String key, String fallback) {
        Object raw = body.get(key);
        return raw == null ? fallback : String.valueOf(raw);
    }

    private int intValue(Map<String, Object> body, String key, int fallback) {
        Object raw = body.get(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String queryParam(HttpExchange exchange, String name, String fallback) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return fallback;
        }
        Map<String, String> parsed = new HashMap<>();
        for (String part : query.split("&")) {
            int idx = part.indexOf('=');
            if (idx > 0) {
                parsed.put(part.substring(0, idx), part.substring(idx + 1));
            }
        }
        return parsed.getOrDefault(name, fallback);
    }

    private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", body);
    }

    private void sendText(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", body);
    }

    private void sendStatus(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", body);
    }

    private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
