package de.regelsuche.web;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
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
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

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
    private final WebSecurityConfig securityConfig;

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
        this(host, port, graphStore, inventoryRepository, exportService, WebSecurityConfig.none());
    }

    public WebWorkbenchServer(
        String host,
        int port,
        ExpressionGraphStore graphStore,
        RuleInventoryRepository inventoryRepository,
        TransformationExportService exportService,
        WebSecurityConfig securityConfig
    ) {
        this.host = host;
        this.port = port;
        this.graphStore = graphStore;
        this.inventoryRepository = inventoryRepository;
        this.exportService = exportService;
        this.securityConfig = securityConfig == null ? WebSecurityConfig.none() : securityConfig;
        this.transformationQuery = new TransformationQueryService(graphStore);
        this.inventoryQuery = new RuleInventoryQueryService(graphStore, inventoryRepository);
        this.exportQuery = new ExportQueryService(graphStore, inventoryRepository);
    }

    public void start() throws IOException {
        if (securityConfig.isTlsEnabled()) {
            HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(host, port), 0);
            httpsServer.setHttpsConfigurator(buildHttpsConfigurator(securityConfig));
            server = httpsServer;
        } else {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
        }
        secure(server.createContext("/api/search", this::handleSearch));
        secure(server.createContext("/api/discover", this::handleDiscover));
        secure(server.createContext("/api/paths", this::handlePaths));
        secure(server.createContext("/api/graph", this::handleGraph));
        secure(server.createContext("/api/search-graph", this::handleSearchGraph));
        secure(server.createContext("/api/identities", this::handleIdentities));
        secure(server.createContext("/api/candidates", this::handleCandidates));
        secure(server.createContext("/api/inventory", this::handleInventory));
        secure(server.createContext("/api/exports", this::handleExports));
        secure(server.createContext("/api/explain", this::handleExplain));
        secure(server.createContext("/", this::handleStatic));
        server.setExecutor(null);
        server.start();
    }

    private void secure(HttpContext context) {
        if (!securityConfig.isAuthEnabled()) {
            return;
        }
        context.setAuthenticator(new BasicAuthenticator(securityConfig.realm()) {
            @Override
            public boolean checkCredentials(String suppliedUsername, String suppliedPassword) {
                return constantTimeEquals(suppliedUsername, securityConfig.username())
                    && constantTimeEquals(suppliedPassword, securityConfig.password());
            }
        });
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            // Still iterate to avoid trivially leaking length differences via timing.
            int diff = a.length ^ b.length;
            for (int i = 0; i < Math.min(a.length, b.length); i++) {
                diff |= a[i] ^ b[i];
            }
            return diff == 0;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    private static HttpsConfigurator buildHttpsConfigurator(WebSecurityConfig config) {
        try {
            KeyStore keystore = KeyStore.getInstance(config.keystoreType());
            try (InputStream input = Files.newInputStream(config.keystorePath())) {
                keystore.load(input, config.keystorePassword());
            }
            KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keystore, config.keystorePassword());
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
            return new HttpsConfigurator(sslContext);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to initialize TLS context", ex);
        }
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
        if (suffix.startsWith("compare")) {
            handlePathComparison(exchange);
            return;
        }
        if (suffix.isEmpty()) {
            String sortParam = queryParam(exchange, "sort", "score");
            int limit = parseIntParam(queryParam(exchange, "limit", "0"), 0);
            de.regelsuche.paths.PathSorters.Mode mode = de.regelsuche.paths.PathSorters.Mode.parse(sortParam);
            List<de.regelsuche.discovery.DiscoveredTransformation> sorted =
                new de.regelsuche.paths.PathSorters().sort(graphStore.discoveredTransformations(), mode);
            if (limit > 0 && sorted.size() > limit) {
                sorted = sorted.subList(0, limit);
            }
            List<de.regelsuche.api.TransformationPathDto> dtos = sorted.stream()
                .map(de.regelsuche.api.TransformationPathDto::from)
                .toList();
            sendJson(exchange, 200, dtoListToJson("transformations", dtos));
            return;
        }
        // /api/paths/{id} or /api/paths/{id}/replay
        if (suffix.endsWith("/replay")) {
            String pathId = suffix.substring(0, suffix.length() - "/replay".length());
            var match = graphStore.discoveredTransformations().stream()
                .filter(t -> t.id().equals(pathId))
                .findFirst();
            if (match.isEmpty()) {
                sendStatus(exchange, 404, "path not found");
                return;
            }
            sendJson(exchange, 200, replayJson(de.regelsuche.api.PathReplayDto.from(match.get(), explanationService)));
            return;
        }
        final String singleId = suffix;
        transformationQuery.pathById(singleId).ifPresentOrElse(
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

    private void handleSearchGraph(HttpExchange exchange) throws IOException {
        de.regelsuche.api.searchgraph.SearchGraphDto graph = buildSearchGraph();
        String filterExpr = queryParam(exchange, "filter", "");
        if (!filterExpr.isBlank()) {
            graph = de.regelsuche.api.searchgraph.SearchGraphFilter.parse(filterExpr).apply(graph);
        }
        sendJson(exchange, 200, de.regelsuche.api.searchgraph.SearchGraphJsonSerializer.toJson(graph));
    }

    private void handlePathComparison(HttpExchange exchange) throws IOException {
        String leftId = queryParam(exchange, "left", "");
        String rightId = queryParam(exchange, "right", "");
        if (leftId.isBlank() || rightId.isBlank()) {
            sendStatus(exchange, 400, "left and right query parameters are required");
            return;
        }
        var transformations = graphStore.discoveredTransformations();
        var leftMatch = transformations.stream().filter(t -> t.id().equals(leftId)).findFirst();
        var rightMatch = transformations.stream().filter(t -> t.id().equals(rightId)).findFirst();
        if (leftMatch.isEmpty() || rightMatch.isEmpty()) {
            sendStatus(exchange, 404, "left or right path not found");
            return;
        }
        de.regelsuche.paths.PathComparisonDto dto =
            new de.regelsuche.paths.PathComparisonService().compare(leftMatch.get(), rightMatch.get());
        sendJson(exchange, 200, pathComparisonToJson(dto));
    }

    private String pathComparisonToJson(de.regelsuche.paths.PathComparisonDto dto) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("leftPathId", dto.leftPathId());
        writer.property("rightPathId", dto.rightPathId());
        writer.stringArray("sharedNodes", dto.sharedNodes());
        writer.stringArray("sharedRules", dto.sharedRules());
        writer.stringArray("leftOnlySteps", dto.leftOnlySteps());
        writer.stringArray("rightOnlySteps", dto.rightOnlySteps());
        writer.array("leftScoreSeries", w -> dto.leftScoreSeries().forEach(w::value));
        writer.array("rightScoreSeries", w -> dto.rightScoreSeries().forEach(w::value));
        writer.property("leftTeachingScore", dto.leftTeachingScore());
        writer.property("rightTeachingScore", dto.rightTeachingScore());
        writer.property("leftProofStatus", dto.leftProofStatus().name());
        writer.property("rightProofStatus", dto.rightProofStatus().name());
        writer.property("leftAssumptionSteps", dto.leftAssumptionSteps());
        writer.property("rightAssumptionSteps", dto.rightAssumptionSteps());
        writer.property("shorterPath", dto.shorterPath());
        writer.property("teachingPreferredPath", dto.teachingPreferredPath());
        writer.property("fewerAssumptionsPath", dto.fewerAssumptionsPath());
        writer.endObject();
        return writer.toString();
    }

    private void handleIdentities(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring("/api/identities".length()).replaceFirst("^/", "");
        de.regelsuche.mining.MacroRuleMiner macroMiner = new de.regelsuche.mining.MacroRuleMiner();
        de.regelsuche.mining.KnownRuleRepository known = new de.regelsuche.mining.KnownRuleRepository();
        var macros = macroMiner.mine(graphStore.discoveredTransformations());

        if (suffix.endsWith("/promote") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String identityId = suffix.substring(0, suffix.length() - "/promote".length());
            var match = macros.stream().filter(m -> m.id().equals(identityId)).findFirst();
            if (match.isEmpty()) {
                sendStatus(exchange, 404, "identity not found");
                return;
            }
            var candidate = match.get();
            String ruleId = "macro-" + Integer.toUnsignedString(candidate.id().hashCode());
            de.regelsuche.inventory.ReusableRule rule = new de.regelsuche.inventory.ReusableRule(
                ruleId,
                candidate.leftPattern(),
                candidate.rightPattern(),
                List.of(),
                candidate.proofStatus(),
                known.statusFor(candidate.leftPattern(), candidate.rightPattern()),
                candidate.occurrences(),
                candidate.compressionRatio(),
                java.time.Instant.now()
            );
            inventoryRepository.save(rule);
            sendJson(exchange, 200, "{\"promotedRuleId\":\"" + escapeJson(ruleId) + "\"}");
            return;
        }
        if (!suffix.isEmpty() && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendStatus(exchange, 405, "method not allowed");
            return;
        }

        // GET /api/identities -> list
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.array("identities", w -> macros.forEach(macro -> {
            var dto = de.regelsuche.api.IdentityReportDto.from(
                macro, known.statusFor(macro.leftPattern(), macro.rightPattern()));
            w.objectValue(inner -> {
                inner.property("id", dto.id());
                inner.property("leftPattern", dto.leftPattern());
                inner.property("rightPattern", dto.rightPattern());
                inner.stringArray("ruleIdSequence", dto.ruleIdSequence());
                inner.property("occurrences", dto.occurrences());
                inner.property("compressionRatio", dto.compressionRatio());
                inner.property("proofStatus", dto.proofStatus().name());
                inner.property("knownRuleStatus", dto.knownRuleStatus().name());
                inner.stringArray("supportingTransformationIds", dto.supportingTransformationIds());
            });
        }));
        writer.endObject();
        sendJson(exchange, 200, writer.toString());
    }

    private List<de.regelsuche.search.SimplificationSuccess> successesFromGraph() {
        // Build SimplificationSuccess records from the discovered transformations
        // we already have in the graph store. This avoids needing a running search
        // service to render /api/search-graph after the fact.
        java.util.List<de.regelsuche.search.SimplificationSuccess> result = new java.util.ArrayList<>();
        for (de.regelsuche.discovery.DiscoveredTransformation transformation : graphStore.discoveredTransformations()) {
            result.add(new de.regelsuche.search.SimplificationSuccess(
                transformation.originalExpression(),
                transformation.improvedExpression(),
                transformation.steps().isEmpty() ? "" : transformation.steps().getLast().ruleId(),
                transformation.steps().size(),
                transformation.totalImprovement(),
                transformation.discoveredAt()
            ));
        }
        return result;
    }

    private static int parseIntParam(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String replayJson(de.regelsuche.api.PathReplayDto dto) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("pathId", dto.pathId());
        writer.array("steps", w -> dto.steps().forEach(step ->
            w.objectValue(inner -> {
                inner.property("stepIndex", step.stepIndex());
                inner.property("fromExpression", step.fromExpression());
                inner.property("fromLatex", step.fromLatex());
                inner.property("toExpression", step.toExpression());
                inner.property("toLatex", step.toLatex());
                inner.property("ruleId", step.ruleId());
                inner.property("ruleExplanation", step.ruleExplanation());
                inner.property("scoreDelta", step.scoreDelta());
                inner.property("equivalencePreserving", step.equivalencePreserving());
            })));
        writer.endObject();
        return writer.toString();
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
        // Sub-resource exports: /api/exports/cluster/{id}.md, /path/{id}.tex, /identity/{id}.md
        if (format.startsWith("cluster/") && format.endsWith(".md")) {
            String clusterId = format.substring("cluster/".length(), format.length() - ".md".length());
            sendText(exchange, 200, renderClusterMarkdown(clusterId));
            return;
        }
        if (format.startsWith("path/") && format.endsWith(".tex")) {
            String pathId = format.substring("path/".length(), format.length() - ".tex".length());
            String tex = renderPathLatex(pathId);
            if (tex == null) {
                sendStatus(exchange, 404, "path not found: " + pathId);
                return;
            }
            sendText(exchange, 200, tex);
            return;
        }
        if (format.startsWith("identity/") && format.endsWith(".md")) {
            String identityId = format.substring("identity/".length(), format.length() - ".md".length());
            String md = renderIdentityMarkdown(identityId);
            if (md == null) {
                sendStatus(exchange, 404, "identity not found: " + identityId);
                return;
            }
            sendText(exchange, 200, md);
            return;
        }
        List<DiscoveredTransformation> transformations = graphStore.discoveredTransformations();
        switch (format.toLowerCase(Locale.ROOT)) {
            case "markdown", "md" -> sendText(exchange, 200, exportService.exportMarkdown(transformations));
            case "latex", "tex" -> sendText(exchange, 200, exportService.exportLatex(transformations));
            case "mermaid", "mmd" -> sendText(exchange, 200, exportService.exportMermaid(transformations));
            case "json" -> sendJson(exchange, 200, exportService.exportBundle(exportQuery.bundle()));
            case "search-graph", "search-graph.json" -> {
                var graph = buildSearchGraph();
                String filterExpr = queryParam(exchange, "filter", "");
                if (!filterExpr.isBlank()) {
                    graph = de.regelsuche.api.searchgraph.SearchGraphFilter.parse(filterExpr).apply(graph);
                }
                sendJson(exchange, 200, exportService.exportSearchGraphJson(graph));
            }
            case "search-graph.mmd" -> {
                var graph = buildSearchGraph();
                String filterExpr = queryParam(exchange, "filter", "");
                if (!filterExpr.isBlank()) {
                    graph = de.regelsuche.api.searchgraph.SearchGraphFilter.parse(filterExpr).apply(graph);
                }
                sendText(exchange, 200, exportService.exportSearchGraphMermaid(graph));
            }
            case "search-graph.graphml" -> sendText(exchange, 200, exportService.exportSearchGraphGraphMl(buildSearchGraph()));
            case "best-path.md", "best-path" -> sendText(exchange, 200, exportService.exportBestPathMarkdown(transformations));
            case "identity-report.tex", "identity-report" -> {
                var macros = new de.regelsuche.mining.MacroRuleMiner().mine(transformations);
                var known = new de.regelsuche.mining.KnownRuleRepository();
                var identities = macros.stream()
                    .map(m -> de.regelsuche.api.IdentityReportDto.from(
                        m, known.statusFor(m.leftPattern(), m.rightPattern())))
                    .toList();
                sendText(exchange, 200, exportService.exportIdentityReportLatex(
                    buildSearchGraph(), transformations, graphStore.ruleCandidates(), identities));
            }
            default -> sendStatus(exchange, 404, "unknown format: " + format);
        }
    }

    private String renderClusterMarkdown(String clusterId) {
        de.regelsuche.api.searchgraph.SearchGraphDto graph = buildSearchGraph();
        de.regelsuche.api.searchgraph.SearchGraphClusterDto cluster = graph.clusters().stream()
            .filter(c -> c.id().equals(clusterId))
            .findFirst()
            .orElse(null);
        if (cluster == null) {
            return "# Cluster nicht gefunden: " + clusterId + "\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Cluster: ").append(cluster.label()).append("\n\n");
        sb.append("- **Id:** `").append(cluster.id()).append("`\n");
        sb.append("- **Typ:** ").append(cluster.type().name()).append("\n");
        sb.append("- **Cohesion:** ").append(String.format(Locale.ROOT, "%.3f", cluster.cohesionScore())).append("\n");
        sb.append("- **Knoten:** ").append(cluster.nodeIds().size()).append("\n");
        if (!cluster.supportingPathIds().isEmpty()) {
            sb.append("- **Supporting paths:** ").append(String.join(", ", cluster.supportingPathIds())).append("\n");
        }
        sb.append("\n## Knoten\n\n");
        for (String n : cluster.nodeIds()) {
            sb.append("- `").append(n).append("`\n");
        }
        return sb.toString();
    }

    private String renderPathLatex(String pathId) {
        var match = graphStore.discoveredTransformations().stream()
            .filter(t -> t.id().equals(pathId))
            .findFirst();
        if (match.isEmpty()) {
            return null;
        }
        return new de.regelsuche.export.AstLatexRenderer().renderPath(match.get());
    }

    private String renderIdentityMarkdown(String identityId) {
        var macros = new de.regelsuche.mining.MacroRuleMiner().mine(graphStore.discoveredTransformations());
        var match = macros.stream().filter(m -> m.id().equals(identityId)).findFirst();
        if (match.isEmpty()) {
            return null;
        }
        var macro = match.get();
        StringBuilder sb = new StringBuilder();
        sb.append("# Identität ").append(macro.id()).append("\n\n");
        sb.append("- **Pattern:** `").append(macro.leftPattern()).append("` → `")
            .append(macro.rightPattern()).append("`\n");
        sb.append("- **Sequenz:** ").append(String.join(" → ", macro.ruleIdSequence())).append("\n");
        sb.append("- **Vorkommen:** ").append(macro.occurrences()).append("\n");
        sb.append("- **Proof:** ").append(macro.proofStatus().name()).append("\n");
        sb.append("- **Supporting Pfade:** ")
            .append(String.join(", ", macro.supportingTransformationIds())).append("\n");
        return sb.toString();
    }

    private de.regelsuche.api.searchgraph.SearchGraphDto buildSearchGraph() {
        var transformations = graphStore.discoveredTransformations();
        var macros = new de.regelsuche.mining.MacroRuleMiner().mine(transformations);
        return new de.regelsuche.api.searchgraph.SearchGraphAssembler().assemble(
            graphStore.snapshot(),
            successesFromGraph(),
            graphStore.ruleCandidates(),
            macros.size(),
            transformations
        );
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
            int limit = securityConfig.maxRequestBytes();
            byte[] buffer = stream.readNBytes(limit + 1);
            if (buffer.length > limit) {
                throw new IOException("request body exceeds limit of " + limit + " bytes");
            }
            String text = new String(buffer, StandardCharsets.UTF_8);
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
