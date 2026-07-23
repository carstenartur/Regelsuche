package de.regelsuche.web;

import de.regelsuche.validation.CandidateProofStatus;

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
import de.regelsuche.export.layout.MathLayoutJsonWriter;
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
import de.regelsuche.plugin.PluginCatalogEntry;
import de.regelsuche.plugin.PluginRuntime;
import de.regelsuche.plugin.PluginRuntimeConfig;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchProfile;
import de.regelsuche.search.TransformationSearchService;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.app.transform.SymPyTransformationEngine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final PluginRuntimeConfig pluginRuntimeConfig;
    private final de.regelsuche.radar.RuleRadarHttpHandler ruleRadarHandler;

    private final TransformationQueryService transformationQuery;
    private final RuleInventoryQueryService inventoryQuery;
    private final ExportQueryService exportQuery;
    private final ExplanationService explanationService = new ExplanationService();
    private final de.regelsuche.search.memory.SearchMemory searchMemory;
    private final de.regelsuche.proof.ProofBridgeService leanProofBridgeService;
    private final de.regelsuche.proof.ProofBridgeService smtProofBridgeService;
    private final de.regelsuche.proof.ProofWorkbenchService proofWorkbenchService;
    private final de.regelsuche.didactic.analytics.DidacticEventStore didacticEventStore;
    private final de.regelsuche.didactic.analytics.DidacticAnalyticsService didacticAnalytics;
    private final de.regelsuche.didactic.StudentStepValidator didacticStepValidator;

    private HttpServer server;
    private final de.regelsuche.didactic.export.EducationalExporter didacticExporter =
        new de.regelsuche.didactic.export.EducationalExporter();

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
        this(host, port, graphStore, inventoryRepository, exportService, securityConfig, PluginRuntimeConfig.defaults());
    }

    public WebWorkbenchServer(
        String host,
        int port,
        ExpressionGraphStore graphStore,
        RuleInventoryRepository inventoryRepository,
        TransformationExportService exportService,
        WebSecurityConfig securityConfig,
        PluginRuntimeConfig pluginRuntimeConfig
    ) {
        this(host, port, graphStore, inventoryRepository, exportService, securityConfig, null,
            defaultProofBridgeService(new de.regelsuche.proof.LeanProofBridge()),
            defaultProofBridgeService(new de.regelsuche.proof.SmtProofBridge()), null, pluginRuntimeConfig);
    }

    public WebWorkbenchServer(
        String host,
        int port,
        ExpressionGraphStore graphStore,
        RuleInventoryRepository inventoryRepository,
        TransformationExportService exportService,
        WebSecurityConfig securityConfig,
        de.regelsuche.search.memory.SearchMemory searchMemory
    ) {
        this(host, port, graphStore, inventoryRepository, exportService, securityConfig, searchMemory,
            defaultProofBridgeService(new de.regelsuche.proof.LeanProofBridge()),
            defaultProofBridgeService(new de.regelsuche.proof.SmtProofBridge()));
    }

    public WebWorkbenchServer(
        String host,
        int port,
        ExpressionGraphStore graphStore,
        RuleInventoryRepository inventoryRepository,
        TransformationExportService exportService,
        WebSecurityConfig securityConfig,
        de.regelsuche.search.memory.SearchMemory searchMemory,
        de.regelsuche.proof.ProofBridgeService leanProofBridgeService,
        de.regelsuche.proof.ProofBridgeService smtProofBridgeService
    ) {
        this(host, port, graphStore, inventoryRepository, exportService,
            securityConfig, searchMemory, leanProofBridgeService, smtProofBridgeService, null,
            PluginRuntimeConfig.defaults());
    }

    public WebWorkbenchServer(
        String host,
        int port,
        ExpressionGraphStore graphStore,
        RuleInventoryRepository inventoryRepository,
        TransformationExportService exportService,
        WebSecurityConfig securityConfig,
        de.regelsuche.search.memory.SearchMemory searchMemory,
        de.regelsuche.proof.ProofBridgeService leanProofBridgeService,
        de.regelsuche.proof.ProofBridgeService smtProofBridgeService,
        de.regelsuche.proof.ProofWorkbenchService proofWorkbenchService
    ) {
        this(host, port, graphStore, inventoryRepository, exportService, securityConfig, searchMemory,
            leanProofBridgeService, smtProofBridgeService, proofWorkbenchService, PluginRuntimeConfig.defaults());
    }

    public WebWorkbenchServer(
        String host,
        int port,
        ExpressionGraphStore graphStore,
        RuleInventoryRepository inventoryRepository,
        TransformationExportService exportService,
        WebSecurityConfig securityConfig,
        de.regelsuche.search.memory.SearchMemory searchMemory,
        de.regelsuche.proof.ProofBridgeService leanProofBridgeService,
        de.regelsuche.proof.ProofBridgeService smtProofBridgeService,
        de.regelsuche.proof.ProofWorkbenchService proofWorkbenchService,
        PluginRuntimeConfig pluginRuntimeConfig
    ) {
        this.host = host;
        this.port = port;
        this.graphStore = graphStore;
        this.inventoryRepository = inventoryRepository;
        this.exportService = exportService;
        this.securityConfig = securityConfig == null ? WebSecurityConfig.none() : securityConfig;
        this.pluginRuntimeConfig = pluginRuntimeConfig == null ? PluginRuntimeConfig.defaults() : pluginRuntimeConfig;
        this.searchMemory = searchMemory == null ? new de.regelsuche.search.memory.SearchMemory() : searchMemory;
        this.leanProofBridgeService = leanProofBridgeService == null
            ? defaultProofBridgeService(new de.regelsuche.proof.LeanProofBridge())
            : leanProofBridgeService;
        this.smtProofBridgeService = smtProofBridgeService == null
            ? defaultProofBridgeService(new de.regelsuche.proof.SmtProofBridge())
            : smtProofBridgeService;
        this.proofWorkbenchService = proofWorkbenchService;
        this.transformationQuery = new TransformationQueryService(graphStore);
        this.inventoryQuery = new RuleInventoryQueryService(graphStore, inventoryRepository);
        this.exportQuery = new ExportQueryService(graphStore, inventoryRepository);
        this.didacticEventStore = createDidacticEventStore();
        this.didacticAnalytics = new de.regelsuche.didactic.analytics.DidacticAnalyticsService(didacticEventStore);
        this.didacticStepValidator = new de.regelsuche.didactic.StudentStepValidator(new SymPyEquivalenceService());
        this.ruleRadarHandler = new de.regelsuche.radar.RuleRadarHttpHandler(
            inventoryRepository, graphStore, this.pluginRuntimeConfig);
    }

    private static de.regelsuche.proof.ProofBridgeService defaultProofBridgeService(
        de.regelsuche.proof.ProofBridge bridge
    ) {
        return new de.regelsuche.proof.ProofBridgeService(bridge);
    }

    private static de.regelsuche.didactic.analytics.DidacticEventStore createDidacticEventStore() {
        Map<String, String> env = System.getenv();
        String jsonPath = readConfig(env, "REGELSUCHE_DIDACTIC_EVENT_STORE");
        if (!jsonPath.isBlank()) {
            try {
                return new de.regelsuche.didactic.analytics.JsonFileDidacticEventStore(Path.of(jsonPath));
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to initialize didactic event store at " + jsonPath, ex);
            }
        }
        int maxEvents = parsePositiveInt(readConfig(env, "REGELSUCHE_DIDACTIC_EVENT_MAX"), 5000);
        return new de.regelsuche.didactic.analytics.InMemoryDidacticEventStore(maxEvents);
    }

    private static String readConfig(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
        }
        return value == null ? "" : value.trim();
    }

    private static int parsePositiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
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
        secure(server.createContext("/api/search-graph/semantic", this::handleSemanticSearchGraph));
        secure(server.createContext("/api/identities", this::handleIdentities));
        secure(server.createContext("/api/candidates", this::handleCandidates));
        secure(server.createContext("/api/inventory", this::handleInventory));
        secure(server.createContext("/api/exports", this::handleExports));
        secure(server.createContext("/api/explain", this::handleExplain));
        secure(server.createContext("/api/analyze", this::handleAnalyze));
        secure(server.createContext("/api/demo", this::handleDemo));
        secure(server.createContext("/api/plugins", this::handlePlugins));
        secure(server.createContext("/api/memory", this::handleMemory));
        secure(server.createContext("/api/proof-status", this::handleProofStatus));
        secure(server.createContext("/api/proof-bridge", this::handleProofBridge));
        secure(server.createContext("/api/proof/jobs", this::handleProofJobs));
        secure(server.createContext("/api/benchmark", this::handleBenchmark));
        secure(server.createContext("/api/didactic", this::handleDidactic));
        secure(server.createContext("/api/inspect", this::handleInspect));
        secure(server.createContext("/api/rule-radar", ruleRadarHandler));
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
        ruleRadarHandler.close();
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
        String goalName = stringValue(body, "goal", "").toUpperCase(Locale.ROOT);
        if (expression.isBlank()) {
            sendStatus(exchange, 400, "expression must not be blank");
            return;
        }
        InputType type;
        SearchProfile profile;
        de.regelsuche.scoring.cost.TransformationGoal goal;
        try {
            type = InputType.valueOf(typeName);
            profile = SearchProfile.valueOf(profileName);
            goal = goalName.isBlank()
                ? profile.defaultGoal()
                : de.regelsuche.scoring.cost.TransformationGoal.valueOf(goalName);
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
            search.submit(new InputRequest(type, expression), goal).join();
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writer.property("profile", profile.name());
            writer.property("goal", goal.name());
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
            sendJson(exchange, 200, replayJson(de.regelsuche.api.PathReplayDto.from(
                match.get(),
                explanationService,
                macroExpansionsFor(match.get())
            )));
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

    private void handleSemanticSearchGraph(HttpExchange exchange) throws IOException {
        de.regelsuche.api.searchgraph.semantic.SemanticGraphViewMode mode =
            de.regelsuche.api.searchgraph.semantic.SemanticGraphViewMode.parse(
                queryParam(exchange, "mode", "semantic"));
        de.regelsuche.api.searchgraph.semantic.SemanticMacroStepDisplay showMacroSteps =
            de.regelsuche.api.searchgraph.semantic.SemanticMacroStepDisplay.parse(
                queryParam(exchange, "showMacroSteps", "compact"));
        boolean showLowSignal = parseBooleanParam(queryParam(exchange, "showLowSignal", "false"));
        boolean showAlternatives = parseBooleanParam(queryParam(exchange, "showAlternatives", "true"));
        boolean showVariants = parseBooleanParam(queryParam(exchange, "showVariants", "false"));
        int maxAlternatives = parseIntParam(queryParam(exchange, "maxAlternatives", "12"), 12);
        int maxVariantsPerCluster = parseIntParam(queryParam(exchange, "maxVariantsPerCluster", "8"), 8);
        String pathId = queryParam(exchange, "pathId", "");
        var graph = buildSemanticSearchGraph(
            mode,
            showMacroSteps,
            showLowSignal,
            showAlternatives,
            showVariants,
            maxAlternatives,
            maxVariantsPerCluster,
            pathId
        );
        sendJson(exchange, 200, de.regelsuche.api.searchgraph.semantic.SemanticSearchGraphJsonSerializer.toJson(graph));
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
            // Use the macro miner's own stable id rather than the truncated
            // 32-bit hashCode (which can collide and overwrite existing rules).
            String ruleId = "macro-" + candidate.id();
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
            // Discovery+ domain tag: infer from the macro's atomic rule-id
            // sequence (equation_*/inequality_*/calculus_*/linalg_* …) and
            // store it as an inventory tag so the UI can filter by domain.
            String domain = new de.regelsuche.mining.MacroDomainInferrer()
                .inferDomain(candidate);
            inventoryRepository.addTag(ruleId, domain);
            sendJson(exchange, 200,
                "{\"promotedRuleId\":\"" + escapeJson(ruleId) + "\",\"domain\":\""
                    + escapeJson(domain) + "\"}");
            return;
        }
        if (!suffix.isEmpty() && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendStatus(exchange, 405, "method not allowed");
            return;
        }

        // GET /api/identities -> list
        de.regelsuche.mining.MacroDomainInferrer domainInferrer =
            new de.regelsuche.mining.MacroDomainInferrer();
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
                inner.property("domain", domainInferrer.inferDomain(macro));
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

    private static boolean parseBooleanParam(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String replayJson(de.regelsuche.api.PathReplayDto dto) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("pathId", dto.pathId());
        writer.property("alignedDerivationLatex", dto.alignedDerivationLatex());
        writer.property("alignedDerivationLatexWithDiff", dto.alignedDerivationLatexWithDiff());
        MathLayoutJsonWriter.write(writer, "derivationLayout", dto.derivationLayout());
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
                inner.property("comparatorFlipped", step.comparatorFlipped());
                writeReplaySpanArray(inner, "changedFromSpans", step.changedFromSpans());
                writeReplaySpanArray(inner, "changedToSpans", step.changedToSpans());
                writeReplayMacroExpansion(inner, step.macroMoveExpansion());
                MathLayoutJsonWriter.write(inner, "layout", step.layout());
            })));
        writer.endObject();
        return writer.toString();
    }

    private static void writeReplayMacroExpansion(
        JsonWriter writer,
        de.regelsuche.mining.MacroMoveExpansion expansion
    ) {
        if (expansion == null) {
            writer.nullProperty("macroMoveExpansion");
            return;
        }
        writer.object("macroMoveExpansion", macro -> {
            macro.property("macroRuleId", expansion.macroRuleId());
            macro.property("fromExpression", expansion.fromExpression());
            macro.property("toExpression", expansion.toExpression());
            macro.property("compressionRatio", expansion.compressionRatio());
            macro.property("expanded", expansion.expanded());
            macro.stringArray("supportingPathIds", expansion.supportingPathIds());
            macro.object("stats", stats -> {
                stats.property("timesConsidered", expansion.stats().timesConsidered());
                stats.property("timesApplied", expansion.stats().timesApplied());
                stats.property("timesImprovedScore", expansion.stats().timesImprovedScore());
                stats.property("averageCostReduction", expansion.stats().averageCostReduction());
                stats.stringArray("usefulForGoals", expansion.stats().usefulForGoals());
            });
            macro.array("atomicSteps", steps -> expansion.atomicSteps().forEach(step ->
                steps.objectValue(inner -> {
                    inner.property("index", step.index());
                    inner.property("beforeExpression", step.beforeExpression());
                    inner.property("afterExpression", step.afterExpression());
                    inner.property("ruleId", step.ruleId());
                    inner.property("ruleKind", step.ruleKind().name());
                    inner.property("scoreBefore", step.scoreBefore());
                    inner.property("scoreAfter", step.scoreAfter());
                    inner.property("equivalencePreserving", step.equivalencePreserving());
                    inner.property("explanation", step.explanation());
                })));
        });
    }

    private java.util.Map<Integer, de.regelsuche.mining.MacroMoveExpansion> macroExpansionsFor(
        de.regelsuche.discovery.DiscoveredTransformation path
    ) {
        MacroExpansionIndex index = macroExpansionIndex();
        java.util.Map<Integer, de.regelsuche.mining.MacroMoveExpansion> byStep = new java.util.LinkedHashMap<>();
        for (de.regelsuche.discovery.TransformationStep step : path.steps()) {
            de.regelsuche.mining.MacroMoveExpansion expansion = index.find(path.id(), step);
            if (expansion != null) {
                byStep.put(step.index(), expansion);
            }
        }
        return byStep;
    }

    private MacroExpansionIndex macroExpansionIndex() {
        java.util.Map<ReplayMacroExpansionKey, java.util.List<de.regelsuche.mining.MacroMoveExpansion>> byPathAndDepth =
            new java.util.HashMap<>();
        java.util.Map<ReplayMacroExpansionKey, java.util.List<de.regelsuche.mining.MacroMoveExpansion>> byPath =
            new java.util.HashMap<>();
        java.util.Map<ReplayMacroExpansionKey, java.util.List<de.regelsuche.mining.MacroMoveExpansion>> relaxed =
            new java.util.HashMap<>();
        for (de.regelsuche.graph.GraphEdge edge : graphStore.snapshot().edges()) {
            de.regelsuche.mining.MacroMoveExpansion expansion = edge.macroMoveExpansion();
            if (expansion == null) {
                continue;
            }
            byPathAndDepth.computeIfAbsent(
                ReplayMacroExpansionKey.of(edge, edge.pathId(), edge.depth()),
                key -> new java.util.ArrayList<>()
            ).add(expansion);
            byPath.computeIfAbsent(
                ReplayMacroExpansionKey.of(edge, edge.pathId(), null),
                key -> new java.util.ArrayList<>()
            ).add(expansion);
            relaxed.computeIfAbsent(
                ReplayMacroExpansionKey.of(edge, "", null),
                key -> new java.util.ArrayList<>()
            ).add(expansion);
        }
        return new MacroExpansionIndex(byPathAndDepth, byPath, relaxed);
    }

    private static de.regelsuche.mining.MacroMoveExpansion uniqueOrNull(
        java.util.Map<ReplayMacroExpansionKey, java.util.List<de.regelsuche.mining.MacroMoveExpansion>> index,
        ReplayMacroExpansionKey key
    ) {
        java.util.List<de.regelsuche.mining.MacroMoveExpansion> matches = index.get(key);
        return matches != null && matches.size() == 1 ? matches.getFirst() : null;
    }

    private record MacroExpansionIndex(
        java.util.Map<ReplayMacroExpansionKey, java.util.List<de.regelsuche.mining.MacroMoveExpansion>> byPathAndDepth,
        java.util.Map<ReplayMacroExpansionKey, java.util.List<de.regelsuche.mining.MacroMoveExpansion>> byPath,
        java.util.Map<ReplayMacroExpansionKey, java.util.List<de.regelsuche.mining.MacroMoveExpansion>> relaxed
    ) {
        private de.regelsuche.mining.MacroMoveExpansion find(
            String pathId,
            de.regelsuche.discovery.TransformationStep step
        ) {
            de.regelsuche.mining.MacroMoveExpansion exact = uniqueOrNull(
                byPathAndDepth,
                ReplayMacroExpansionKey.of(step, pathId + "#" + step.index(), step.index())
            );
            if (exact != null) {
                return exact;
            }
            de.regelsuche.mining.MacroMoveExpansion pathOnly = uniqueOrNull(
                byPath,
                ReplayMacroExpansionKey.of(step, pathId + "#" + step.index(), null)
            );
            if (pathOnly != null) {
                return pathOnly;
            }
            de.regelsuche.mining.MacroMoveExpansion rootPathExact = uniqueOrNull(
                byPathAndDepth,
                ReplayMacroExpansionKey.of(step, pathId, step.index())
            );
            if (rootPathExact != null) {
                return rootPathExact;
            }
            de.regelsuche.mining.MacroMoveExpansion rootPathOnly = uniqueOrNull(
                byPath,
                ReplayMacroExpansionKey.of(step, pathId, null)
            );
            if (rootPathOnly != null) {
                return rootPathOnly;
            }
            return uniqueOrNull(relaxed, ReplayMacroExpansionKey.of(step, "", null));
        }
    }

    private record ReplayMacroExpansionKey(
        String fromExpression,
        String toExpression,
        String ruleId,
        int scoreBefore,
        int scoreAfter,
        String pathId,
        Integer depth
    ) {
        private static ReplayMacroExpansionKey of(de.regelsuche.graph.GraphEdge edge, String pathId, Integer depth) {
            return new ReplayMacroExpansionKey(
                edge.fromExpression(),
                edge.toExpression(),
                edge.transformationRule(),
                edge.scoreBefore(),
                edge.scoreAfter(),
                pathId == null ? "" : pathId,
                depth
            );
        }

        private static ReplayMacroExpansionKey of(
            de.regelsuche.discovery.TransformationStep step,
            String pathId,
            Integer depth
        ) {
            return new ReplayMacroExpansionKey(
                step.beforeExpression(),
                step.afterExpression(),
                step.ruleId(),
                step.scoreBefore(),
                step.scoreAfter(),
                pathId == null ? "" : pathId,
                depth
            );
        }
    }

    private static void writeReplaySpanArray(JsonWriter writer, String key, java.util.List<int[]> spans) {
        writer.array(key, w -> {
            for (int[] span : spans) {
                int start = span.length > 0 ? span[0] : 0;
                int length = span.length > 1 ? span[1] : 0;
                w.arrayValue(inner -> inner.value(start).value(length));
            }
        });
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

    private void handlePlugins(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendStatus(exchange, 405, "method not allowed");
            return;
        }
        String suffix = exchange.getRequestURI().getPath().substring("/api/plugins".length()).replaceFirst("^/", "");
        String activeProfile = queryParam(exchange, "profile",
            pluginRuntimeConfig.activeProfile() == null ? "" : pluginRuntimeConfig.activeProfile());
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            pluginRuntimeConfig.pluginsDirectory(),
            pluginRuntimeConfig.rulesDirectory(),
            pluginRuntimeConfig.loadClasspathPlugins(),
            pluginRuntimeConfig.disabledPluginIds(),
            pluginRuntimeConfig.disabledRuleIds(),
            activeProfile
        ))) {
            if (suffix.isEmpty()) {
                sendJson(exchange, 200, renderPluginRuntimeJson(runtime));
                return;
            }
            switch (suffix) {
                case "rules" -> sendJson(exchange, 200, renderPluginRulesJson(runtime));
                case "profiles" -> sendJson(exchange, 200, renderPluginProfilesJson(runtime));
                default -> sendStatus(exchange, 404, "unknown plugin endpoint: " + suffix);
            }
        }
    }

    private String renderPluginRuntimeJson(PluginRuntime runtime) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("activeProfile", runtime.activeProfile() == null ? "" : runtime.activeProfile());
        writer.array("plugins", array -> runtime.loadedPlugins().forEach(plugin -> {
            PluginCatalogEntry entry = PluginCatalogEntry.from(plugin);
            array.objectValue(inner -> {
                inner.property("id", entry.id());
                inner.property("name", entry.name());
                inner.property("version", entry.version());
                inner.property("source", entry.source());
                inner.property("enabled", entry.enabled());
                inner.property("apiVersion", entry.apiVersion());
                inner.property("minimumCoreVersion", entry.minimumCoreVersion());
                inner.property("compatibility", entry.compatibility());
                inner.stringArray("compatibilityIssues", entry.compatibilityIssues());
                inner.stringArray("capabilities", entry.capabilities());
                inner.array("dependencies", dependencies -> entry.dependencies().forEach(dependency ->
                dependencies.objectValue(value -> {
                    value.property("pluginId", dependency.pluginId());
                    value.property("versionConstraint", dependency.versionConstraint());
                    value.property("optional", dependency.optional());
                    value.property("status", dependency.status());
                })
                ));
                inner.property("provenance", entry.provenance());
                inner.property("signaturePresent", entry.signaturePresent());
                inner.property("signatureVerified", entry.signatureVerified());
                inner.property("trustedSource", entry.trustedSource());
                inner.stringArray("trustWarnings", entry.trustWarnings());
            });
        }));
        writer.array("rules", array -> writePluginRules(runtime, array));
        writer.array("profiles", array -> writePluginProfiles(runtime, array));
        writer.array("diagnostics", array -> runtime.diagnostics().forEach(diagnostic -> array.objectValue(inner -> {
            inner.property("source", diagnostic.source());
            inner.property("message", diagnostic.message());
        })));
        writer.endObject();
        return writer.toString();
    }

    private String renderPluginRulesJson(PluginRuntime runtime) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("activeProfile", runtime.activeProfile() == null ? "" : runtime.activeProfile());
        writer.array("rules", array -> writePluginRules(runtime, array));
        writer.endObject();
        return writer.toString();
    }

    private String renderPluginProfilesJson(PluginRuntime runtime) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("activeProfile", runtime.activeProfile() == null ? "" : runtime.activeProfile());
        writer.array("profiles", array -> writePluginProfiles(runtime, array));
        writer.endObject();
        return writer.toString();
    }

    private void writePluginRules(PluginRuntime runtime, JsonWriter array) {
        runtime.registeredRules().forEach(rule -> array.objectValue(inner -> {
            inner.property("id", rule.id());
            inner.property("type", rule.type());
            inner.property("source", rule.source());
            inner.property("enabled", rule.enabled());
            inner.property("explanation", rule.explanation());
            inner.stringArray("tags", rule.tags());
        }));
        runtime.macroRegistry().registrations().forEach(macro -> array.objectValue(inner -> {
            inner.property("id", macro.id());
            inner.property("type", "macro");
            inner.property("source", macro.source());
            inner.property("enabled", macro.enabled());
            inner.property("explanation", macro.macro().explanation());
            inner.stringArray("tags", macro.macro().tags());
        }));
    }

    private void writePluginProfiles(PluginRuntime runtime, JsonWriter array) {
        runtime.profiles().forEach(profile -> array.objectValue(inner -> {
            inner.property("id", profile.id());
            inner.property("source", profile.source());
            inner.property("active", profile.id().equals(runtime.activeProfile()));
            inner.stringArray("enableTags", profile.enableTags());
            inner.stringArray("disableTags", profile.disableTags());
            inner.stringArray("whitelist", profile.whitelist());
            inner.stringArray("blacklist", profile.blacklist());
        }));
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
        if (format.equals("bundle.zip")) {
            byte[] zipBytes = buildReportBundleZip();
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.getResponseHeaders().add(
                "Content-Disposition", "attachment; filename=\"regelsuche-report-bundle.zip\"");
            exchange.sendResponseHeaders(200, zipBytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(zipBytes);
            }
            return;
        }
        if (format.startsWith("search-analysis-report")) {
            var report = new de.regelsuche.export.SearchAnalysisReportService();
            var ctx = analysisReportContext();
            switch (format) {
                case "search-analysis-report.md" -> sendText(exchange, 200, report.renderMarkdown(ctx));
                case "search-analysis-report.tex" -> sendText(exchange, 200, report.renderLatex(ctx));
                case "search-analysis-report.json" -> sendJson(exchange, 200, report.renderJson(ctx));
                default -> sendStatus(exchange, 404, "unknown analysis report format: " + format);
            }
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
            case "search-graph-semantic", "search-graph-semantic.json" -> {
                var graph = buildSemanticSearchGraph(
                    de.regelsuche.api.searchgraph.semantic.SemanticGraphViewMode.parse(
                        queryParam(exchange, "mode", "semantic")),
                    de.regelsuche.api.searchgraph.semantic.SemanticMacroStepDisplay.parse(
                        queryParam(exchange, "showMacroSteps", "compact")),
                    parseBooleanParam(queryParam(exchange, "showLowSignal", "false")),
                    parseBooleanParam(queryParam(exchange, "showAlternatives", "true")),
                    parseBooleanParam(queryParam(exchange, "showVariants", "false")),
                    parseIntParam(queryParam(exchange, "maxAlternatives", "12"), 12),
                    parseIntParam(queryParam(exchange, "maxVariantsPerCluster", "8"), 8),
                    queryParam(exchange, "pathId", "")
                );
                sendJson(exchange, 200, de.regelsuche.api.searchgraph.semantic.SemanticSearchGraphJsonSerializer.toJson(graph));
            }
            case "search-graph.mmd" -> {
                var graph = buildSearchGraph();
                String filterExpr = queryParam(exchange, "filter", "");
                if (!filterExpr.isBlank()) {
                    graph = de.regelsuche.api.searchgraph.SearchGraphFilter.parse(filterExpr).apply(graph);
                }
                sendText(exchange, 200, exportService.exportSearchGraphMermaid(graph));
            }
            case "search-graph-semantic.mmd" -> {
                var graph = buildSemanticSearchGraph(
                    de.regelsuche.api.searchgraph.semantic.SemanticGraphViewMode.parse(
                        queryParam(exchange, "mode", "semantic")),
                    de.regelsuche.api.searchgraph.semantic.SemanticMacroStepDisplay.parse(
                        queryParam(exchange, "showMacroSteps", "compact")),
                    parseBooleanParam(queryParam(exchange, "showLowSignal", "false")),
                    parseBooleanParam(queryParam(exchange, "showAlternatives", "true")),
                    parseBooleanParam(queryParam(exchange, "showVariants", "false")),
                    parseIntParam(queryParam(exchange, "maxAlternatives", "12"), 12),
                    parseIntParam(queryParam(exchange, "maxVariantsPerCluster", "8"), 8),
                    queryParam(exchange, "pathId", "")
                );
                sendText(exchange, 200, de.regelsuche.api.searchgraph.semantic.SemanticSearchGraphJsonSerializer.toMermaid(graph));
            }
            case "search-graph.graphml" -> {
                var graph = buildSearchGraph();
                String filterExpr = queryParam(exchange, "filter", "");
                if (!filterExpr.isBlank()) {
                    graph = de.regelsuche.api.searchgraph.SearchGraphFilter.parse(filterExpr).apply(graph);
                }
                sendText(exchange, 200, exportService.exportSearchGraphGraphMl(graph));
            }
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

    private de.regelsuche.api.searchgraph.semantic.SemanticSearchGraphDto buildSemanticSearchGraph(
        de.regelsuche.api.searchgraph.semantic.SemanticGraphViewMode mode,
        de.regelsuche.api.searchgraph.semantic.SemanticMacroStepDisplay showMacroSteps,
        boolean showLowSignal,
        boolean showAlternatives,
        boolean showVariants,
        int maxAlternatives,
        int maxVariantsPerCluster,
        String pathId
    ) {
        var transformations = graphStore.discoveredTransformations();
        var rawGraph = buildSearchGraph();
        var selectedTransformations = selectSemanticTransformations(transformations, pathId);
        var macroRules = new de.regelsuche.mining.MacroRuleMiner().mine(selectedTransformations);
        return new de.regelsuche.api.searchgraph.semantic.SemanticSearchGraphAssembler().assemble(
            rawGraph,
            selectedTransformations,
            macroRules,
            mode,
            showMacroSteps,
            showLowSignal,
            showAlternatives,
            showVariants,
            maxAlternatives,
            maxVariantsPerCluster
        );
    }

    private List<de.regelsuche.discovery.DiscoveredTransformation> selectSemanticTransformations(
        List<de.regelsuche.discovery.DiscoveredTransformation> transformations,
        String pathId
    ) {
        if (pathId == null || pathId.isBlank()) {
            return transformations;
        }
        var selected = transformations.stream()
            .filter(transformation -> pathId.equals(transformation.id()))
            .toList();
        if (selected.isEmpty()) {
            return List.of();
        }
        return selected;
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

    private void handleAnalyze(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring("/api/analyze".length()).replaceFirst("^/", "");
        if (!"move".equals(suffix)) {
            sendStatus(exchange, 404, "expected /api/analyze/move?expression=...");
            return;
        }
        String expression = queryParam(exchange, "expression", "");
        if (expression.isBlank()) {
            sendStatus(exchange, 400, "expression query parameter is required");
            return;
        }
        de.regelsuche.api.searchgraph.SearchGraphDto graph = buildSearchGraph();
        de.regelsuche.analyze.MoveAnalysisDto analysis =
            new de.regelsuche.analyze.MoveAnalysisService().analyze(graph, expression);
        sendJson(exchange, 200, moveAnalysisToJson(analysis));
    }

    /**
     * Rule Authoring IDE – tree-local rule inspection endpoint.
     *
     * <ul>
     *   <li>{@code GET /api/inspect/tree?expression=...&selectedPathKey=...} —
     *       returns all tree-position grouped rule matches for the given
     *       expression, including bindings and rewrite previews; when
     *       {@code selectedPathKey} is provided, that position is marked as
     *       selected in the response.</li>
     *   <li>{@code POST /api/inspect/tree/apply} — applies a selected match
     *       identified by {@code expression}, {@code pathKey}, and stable
     *       {@code matchId}; the legacy {@code matchIndex} is still accepted for
     *       compatibility. The response returns the rewritten expression plus a
     *       refreshed inspection model.</li>
     * </ul>
     */
    private void handleInspect(HttpExchange exchange) throws IOException {
        String apiPath = exchange.getRequestURI().getPath();
        String suffix = apiPath.substring("/api/inspect".length()).replaceFirst("^/", "");
        if ("tree/apply".equals(suffix)) {
            handleInspectApply(exchange);
            return;
        }
        if (!"tree".equals(suffix)) {
            sendStatus(exchange, 404, "expected /api/inspect/tree?expression=...");
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendStatus(exchange, 405, "method not allowed");
            return;
        }
        String expression = queryParam(exchange, "expression", "");
        if (expression.isBlank()) {
            sendStatus(exchange, 400, "expression query parameter is required");
            return;
        }
        String selectedPathKey = queryParam(exchange, "selectedPathKey", "");
        de.regelsuche.ide.RuleInspectionDto dto =
                new de.regelsuche.ide.RuleInspectionService().inspect(
                        expression,
                        selectedPathKey.isBlank() ? null : selectedPathKey);
        sendJson(exchange, 200, ruleInspectionToJson(dto));
    }

    private void handleInspectApply(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendStatus(exchange, 405, "method not allowed");
            return;
        }
        Map<String, Object> body = readJsonObject(exchange);
        String expression = stringValue(body, "expression", "");
        String pathKey = stringValue(body, "pathKey", "");
        String matchId = stringValue(body, "matchId", "");
        int matchIndex = intValue(body, "matchIndex", -1);
        if (expression.isBlank() || pathKey.isBlank() || (matchId.isBlank() && matchIndex < 0)) {
            sendStatus(exchange, 400, "expression, pathKey and matchId (or legacy matchIndex) are required");
            return;
        }

        de.regelsuche.ide.RuleInspectionService service = new de.regelsuche.ide.RuleInspectionService();
        de.regelsuche.ide.RuleInspectionDto inspection = service.inspect(expression, pathKey);
        var selectedPosition = inspection.positions().stream()
                .filter(position -> pathKey.equals(position.pathKey()))
                .findFirst();
        if (selectedPosition.isEmpty()) {
            sendStatus(exchange, 409, "selected position no longer exists");
            return;
        }
        if (matchIndex >= selectedPosition.get().matches().size()) {
            sendStatus(exchange, 409, "selected match no longer exists");
            return;
        }
        de.regelsuche.ide.RuleInspectionDto.RuleMatch match = null;
        int resolvedMatchIndex = -1;
        if (!matchId.isBlank()) {
            for (int i = 0; i < selectedPosition.get().matches().size(); i++) {
                de.regelsuche.ide.RuleInspectionDto.RuleMatch candidate = selectedPosition.get().matches().get(i);
                if (matchId.equals(candidate.matchId())) {
                    match = candidate;
                    resolvedMatchIndex = i;
                    break;
                }
            }
            if (match == null) {
                sendStatus(exchange, 409, "selected match no longer exists");
                return;
            }
        } else {
            match = selectedPosition.get().matches().get(matchIndex);
            resolvedMatchIndex = matchIndex;
        }
        if (!match.applicable() || match.expressionAfter() == null || match.expressionAfter().isBlank()) {
            sendStatus(exchange, 409, "selected match is not applicable");
            return;
        }
        de.regelsuche.ide.RuleInspectionDto refreshed = service.inspect(match.expressionAfter(), pathKey);

        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("expressionBefore", expression);
        writer.property("expressionAfter", match.expressionAfter());
        writer.property("pathKey", pathKey);
        writer.property("matchId", match.matchId());
        writer.property("matchIndex", resolvedMatchIndex);
        writer.property("kind", match.kind());
        writer.object("inspection", inspectionWriter -> writeRuleInspection(inspectionWriter, refreshed));
        writer.endObject();
        sendJson(exchange, 200, writer.toString());
    }

    private String ruleInspectionToJson(de.regelsuche.ide.RuleInspectionDto dto) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writeRuleInspection(writer, dto);
        writer.endObject();
        return writer.toString();
    }

    private void writeRuleInspection(JsonWriter writer, de.regelsuche.ide.RuleInspectionDto dto) {
        writer.property("expression", dto.expression());
        writer.array("positions", pw -> dto.positions().forEach(pos ->
                pw.objectValue(pobj -> {
                    pobj.property("pathKey", pos.pathKey());
                    pobj.property("subtree", pos.subtree());
                    pobj.property("selected", pos.selected());
                    pobj.array("matches", mw -> pos.matches().forEach(match ->
                            mw.objectValue(mobj -> {
                        mobj.property("matchId", match.matchId());
                        mobj.property("enumeratorId", match.enumeratorId());
                        mobj.property("kind", match.kind());
                        mobj.property("applicable", match.applicable());
                                mobj.property("rewriteBefore", match.rewriteBefore());
                                mobj.property("rewriteAfter", match.rewriteAfter());
                                mobj.property("subtreeBefore", match.subtreeBefore());
                                mobj.property("subtreeAfter", match.subtreeAfter());
                                mobj.property("expressionAfter", match.expressionAfter());
                                mobj.array("bindings", bw -> match.bindings().forEach(binding ->
                                        bw.objectValue(bobj -> {
                                            bobj.property("name", binding.name());
                                            bobj.property("value", binding.value());
                                            bobj.property("kind", binding.kind());
                                        })));
                            })));
                })));
    }

    /**
     * Didactic learning-system endpoints (PR 17):
     * <ul>
     *   <li>{@code POST /api/didactic/step-check} —
     *       body {@code {"currentExpression": "...", "studentStep": "...",
     *       "difficulty": "MITTELSTUFE"}}; returns the
     *       {@link de.regelsuche.didactic.StudentStepValidator.Result}.</li>
     *   <li>{@code POST /api/didactic/hint/{pathId}} —
     *       body {@code {"currentExpression": "...", "pedagogyProfile":
     *       "SCHOOL"}}; returns the graduated hint sequence for the next
     *       step of the named derivation.</li>
     *   <li>{@code GET  /api/didactic/misconceptions} — lists the built-in
     *       misconception catalogue.</li>
     * </ul>
     */
    private void handleDidactic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring("/api/didactic".length()).replaceFirst("^/", "");
        if (suffix.startsWith("step-check")) {
            handleDidacticStepCheck(exchange);
            return;
        }
        if (suffix.startsWith("hint/")) {
            handleDidacticHint(exchange, suffix.substring("hint/".length()));
            return;
        }
        if (suffix.startsWith("hint")) {
            sendStatus(exchange, 400, "expected /api/didactic/hint/{pathId}");
            return;
        }
        if (suffix.startsWith("misconceptions")) {
            handleDidacticMisconceptions(exchange);
            return;
        }
        if (suffix.startsWith("analytics")) {
            handleDidacticAnalytics(exchange);
            return;
        }
        if (suffix.startsWith("replay/")) {
            handleDidacticReplay(exchange, suffix.substring("replay/".length()));
            return;
        }
        if (suffix.startsWith("replay")) {
            sendStatus(exchange, 400, "expected /api/didactic/replay/{pathId}");
            return;
        }
        if (suffix.startsWith("export/")) {
            handleDidacticExport(exchange, suffix.substring("export/".length()));
            return;
        }
        if (suffix.startsWith("export")) {
            sendStatus(exchange, 400,
                "expected /api/didactic/export/{worksheet|solution|teacher}/{pathId}.md");
            return;
        }
        if (suffix.isEmpty()) {
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writer.object("endpoints", inner -> {
                inner.property("stepCheck", "/api/didactic/step-check");
                inner.property("hint", "/api/didactic/hint/{pathId}");
                inner.property("misconceptions", "/api/didactic/misconceptions");
                inner.property("analytics", "/api/didactic/analytics");
                inner.property("replay", "/api/didactic/replay/{pathId}");
                inner.property("exportWorksheet", "/api/didactic/export/worksheet/{pathId}.md");
                inner.property("exportSolution",  "/api/didactic/export/solution/{pathId}.md");
                inner.property("exportTeacher",   "/api/didactic/export/teacher/{pathId}.md");
            });
            writer.endObject();
            sendJson(exchange, 200, writer.toString());
            return;
        }
        sendStatus(exchange, 404, "unknown didactic endpoint: " + suffix);
    }

    private void handleDidacticStepCheck(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendStatus(exchange, 405, "method not allowed");
            return;
        }
        Map<String, Object> body = readJsonObject(exchange);
        String currentExpression = stringValue(body, "currentExpression", "");
        String studentStep       = stringValue(body, "studentStep", "");
        String difficulty        = stringValue(body, "difficulty", "MITTELSTUFE");
        if (currentExpression.isBlank() || studentStep.isBlank()) {
            sendStatus(exchange, 400,
                "currentExpression and studentStep are required");
            return;
        }
        de.regelsuche.didactic.DifficultyLevel level;
        try {
            level = de.regelsuche.didactic.DifficultyLevel.valueOf(
                difficulty.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            sendStatus(exchange, 400, "invalid difficulty: " + difficulty);
            return;
        }
        de.regelsuche.didactic.StudentStepValidator.Result result =
            didacticStepValidator.validate(currentExpression, studentStep, level);

        didacticEventStore.record(de.regelsuche.didactic.analytics.DidacticEvent.stepCheck(
            java.time.Instant.now(),
            level,
            result.correct(),
            result.didacticallyAppropriate(),
            result.misconception().map(de.regelsuche.didactic.MisconceptionRule::id).orElse(null)
        ));

        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("correct", result.correct());
        writer.property("didacticallyAppropriate", result.didacticallyAppropriate());
        writer.property("message", result.message());
        writer.property("difficulty", level.name());
        result.misconception().ifPresent(rule -> writer.object("misconception", inner -> {
            inner.property("id", rule.id());
            inner.property("wrongRulePattern", rule.wrongRulePattern());
            inner.property("typicalCause", rule.typicalCause());
            inner.property("explanation", rule.explanation());
            inner.property("correctionSuggestion", rule.correctionSuggestion());
        }));
        writer.endObject();
        sendJson(exchange, 200, writer.toString());
    }

    private void handleDidacticHint(HttpExchange exchange, String pathId) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendStatus(exchange, 405, "method not allowed");
            return;
        }
        if (pathId.isBlank()) {
            sendStatus(exchange, 400, "expected /api/didactic/hint/{pathId}");
            return;
        }
        var match = graphStore.discoveredTransformations().stream()
            .filter(t -> t.id().equals(pathId))
            .findFirst();
        if (match.isEmpty()) {
            sendStatus(exchange, 404, "path not found");
            return;
        }
        Map<String, Object> body = readJsonObject(exchange);
        String currentExpression = stringValue(body, "currentExpression", "");
        String profile = stringValue(body, "pedagogyProfile", "SCHOOL");

        de.regelsuche.didactic.PedagogyProfile profileEnum;
        try {
            profileEnum = de.regelsuche.didactic.PedagogyProfile.valueOf(
                profile.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            sendStatus(exchange, 400, "invalid pedagogyProfile: " + profile);
            return;
        }
        var hints = new de.regelsuche.didactic.HintGenerator()
            .hintsFor(match.get(), currentExpression, profileEnum);

        de.regelsuche.didactic.HintGenerator.Strength deliveredStrength = hints.isEmpty()
            ? null
            : hints.get(hints.size() - 1).strength();
        didacticEventStore.record(de.regelsuche.didactic.analytics.DidacticEvent.hint(
            java.time.Instant.now(),
            pathId,
            profileEnum,
            deliveredStrength
        ));

        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("pathId", pathId);
        writer.property("pedagogyProfile", profileEnum.name());
        writer.array("hints", arr -> hints.forEach(hint ->
            arr.objectValue(inner -> {
                inner.property("strength", hint.strength().name());
                inner.property("text", hint.text());
            })));
        writer.endObject();
        sendJson(exchange, 200, writer.toString());
    }

    private void handleDidacticMisconceptions(HttpExchange exchange) throws IOException {
        var detector = new de.regelsuche.didactic.MisconceptionDetector();
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.array("misconceptions", arr -> detector.catalogue().forEach(rule ->
            arr.objectValue(inner -> {
                inner.property("id", rule.id());
                inner.property("wrongRulePattern", rule.wrongRulePattern());
                inner.property("typicalCause", rule.typicalCause());
                inner.property("explanation", rule.explanation());
                inner.property("correctionSuggestion", rule.correctionSuggestion());
            })));
        writer.endObject();
        sendJson(exchange, 200, writer.toString());
    }

    private void handleDidacticAnalytics(HttpExchange exchange) throws IOException {
        var snapshot = didacticAnalytics.snapshot();
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("totalEvents", snapshot.totalEvents());
        writer.property("stepChecks", snapshot.stepChecks());
        writer.property("hints", snapshot.hints());
        writer.property("correctSteps", snapshot.correctSteps());
        writer.property("didacticallyAppropriateSteps", snapshot.didacticallyAppropriateSteps());
        writer.property("accuracy", snapshot.accuracy());
        writer.property("appropriateness", snapshot.appropriateness());
        writer.object("misconceptionFrequency", inner -> snapshot.misconceptionFrequency()
            .forEach(inner::property));
        writer.object("stepChecksByDifficulty", inner -> snapshot.stepChecksByDifficulty()
            .forEach((k, v) -> inner.property(k.name(), v)));
        writer.object("hintsByStrength", inner -> snapshot.hintsByStrength()
            .forEach((k, v) -> inner.property(k.name(), v)));
        writer.object("hintsByProfile", inner -> snapshot.hintsByProfile()
            .forEach((k, v) -> inner.property(k.name(), v)));
        writer.endObject();
        sendJson(exchange, 200, writer.toString());
    }

    private void handleDidacticReplay(HttpExchange exchange, String pathId) throws IOException {
        if (pathId.isBlank()) {
            sendStatus(exchange, 400, "expected /api/didactic/replay/{pathId}");
            return;
        }
        var match = graphStore.discoveredTransformations().stream()
            .filter(t -> t.id().equals(pathId))
            .findFirst();
        if (match.isEmpty()) {
            sendStatus(exchange, 404, "path not found");
            return;
        }
        var derivation = match.get();
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("pathId", pathId);
        writer.property("originalExpression", derivation.originalExpression());
        writer.property("improvedExpression", derivation.improvedExpression());
        writer.array("steps", arr -> derivation.steps().forEach(step ->
            arr.objectValue(inner -> {
                inner.property("index", step.index());
                inner.property("beforeExpression", step.beforeExpression());
                inner.property("afterExpression", step.afterExpression());
                inner.property("ruleId", step.ruleId());
                inner.property("ruleKind", step.ruleKind().name());
                inner.property("explanation", step.explanation());
                inner.array("diffTokens", tokensArr ->
                    de.regelsuche.didactic.SymbolDiff.diff(
                            step.beforeExpression(), step.afterExpression())
                        .forEach(token -> tokensArr.objectValue(tokenObj -> {
                            tokenObj.property("text", token.text());
                            tokenObj.property("change", token.change().name());
                        })));
            })));
        writer.endObject();
        sendJson(exchange, 200, writer.toString());
    }

    private void handleDidacticExport(HttpExchange exchange, String suffix) throws IOException {
        // suffix is e.g. "worksheet/some-path-id.md"
        int slash = suffix.indexOf('/');
        if (slash <= 0 || slash >= suffix.length() - 1) {
            sendStatus(exchange, 400,
                "expected /api/didactic/export/{worksheet|solution|teacher}/{pathId}.md");
            return;
        }
        String kind = suffix.substring(0, slash);
        String rest = suffix.substring(slash + 1);
        if (rest.endsWith(".md")) {
            rest = rest.substring(0, rest.length() - 3);
        }
        if (rest.isBlank()) {
            sendStatus(exchange, 400, "missing pathId");
            return;
        }
        final String pathId = rest;
        var match = graphStore.discoveredTransformations().stream()
            .filter(t -> t.id().equals(pathId))
            .findFirst();
        if (match.isEmpty()) {
            sendStatus(exchange, 404, "path not found");
            return;
        }
        String body = switch (kind.toLowerCase(Locale.ROOT)) {
            case "worksheet" -> didacticExporter.worksheet(match.get());
            case "solution"  -> didacticExporter.solution(match.get());
            case "teacher"   -> didacticExporter.teacherMode(match.get());
            default          -> null;
        };
        if (body == null) {
            sendStatus(exchange, 404, "unknown export kind: " + kind);
            return;
        }
        send(exchange, 200, "text/markdown; charset=utf-8", body);
    }

    private String moveAnalysisToJson(de.regelsuche.analyze.MoveAnalysisDto a) {
        JsonWriter w = new JsonWriter();
        w.beginObject();
        w.property("expression", a.expression());
        w.property("reason", a.reason());
        w.property("mostUsefulRule", a.mostUsefulRule());
        if (a.bestMove() != null) {
            w.object("bestMove", inner -> writeMove(inner, a.bestMove()));
        }
        w.array("alternatives", arr -> {
            for (var m : a.alternatives()) {
                arr.objectValue(o -> writeMove(o, m));
            }
        });
        w.array("deadEnds", arr -> {
            for (var m : a.deadEnds()) {
                arr.objectValue(o -> writeMove(o, m));
            }
        });
        w.endObject();
        return w.toString();
    }

    private static void writeMove(JsonWriter w, de.regelsuche.analyze.MoveAnalysisDto.Move m) {
        w.property("ruleId", m.ruleId());
        w.property("ruleKind", m.ruleKind());
        w.property("toExpression", m.toExpression());
        w.property("toLatex", m.toLatex());
        w.property("scoreDelta", m.scoreDelta());
        w.property("deadEnd", m.deadEnd());
        w.property("isBest", m.isBest());
        w.property("equivalencePreserving", m.equivalencePreserving());
        w.stringArray("assumptions", m.assumptions());
        w.stringArray("pathIds", m.pathIds());
    }

    private de.regelsuche.export.SearchAnalysisReportService.SearchAnalysisReportContext analysisReportContext() {
        var transformations = graphStore.discoveredTransformations();
        var best = transformations.stream()
            .max(java.util.Comparator.comparingInt(DiscoveredTransformation::totalImprovement))
            .orElse(null);
        java.util.List<DiscoveredTransformation> alternatives;
        if (best != null) {
            alternatives = transformations.stream().filter(t -> !t.id().equals(best.id())).toList();
        } else {
            alternatives = java.util.List.of();
        }
        var macros = new de.regelsuche.mining.MacroRuleMiner().mine(transformations);
        var known = new de.regelsuche.mining.KnownRuleRepository();
        var identities = macros.stream()
            .map(m -> de.regelsuche.api.IdentityReportDto.from(m, known.statusFor(m.leftPattern(), m.rightPattern())))
            .toList();
        java.util.Set<String> assumptions = new java.util.LinkedHashSet<>();
        for (var t : transformations) {
            for (var step : t.steps()) {
                if (!step.equivalencePreserving()) {
                    assumptions.add("Pfad " + t.id() + ", Schritt " + step.index() + ": " + step.ruleId());
                }
            }
        }
        return new de.regelsuche.export.SearchAnalysisReportService.SearchAnalysisReportContext(
            best == null ? "" : best.originalExpression(),
            "DISCOVERY",
            java.util.List.of("core"),
            buildSearchGraph(),
            best,
            alternatives,
            macros,
            identities,
            new java.util.ArrayList<>(assumptions),
            inventoryRepository.findAll()
        );
    }

    private String runMacroLearningDemo() {
        String[] expressions = {"(x+1)^2", "(x+2)^2", "(x+3)^2", "(x+7)^2"};
        de.regelsuche.learning.MacroRuleLearningService learner =
            new de.regelsuche.learning.MacroRuleLearningService(inventoryRepository);
        de.regelsuche.canonical.ExpressionCanonicalizer canonicalizer =
            new de.regelsuche.canonical.ExpressionCanonicalizer();
        de.regelsuche.scoring.ExpressionScorer scorer = new de.regelsuche.scoring.ExpressionScorer();
        de.regelsuche.transform.TransformationEngine engine =
            new de.regelsuche.transform.AstRewriteTransformationEngine(
                de.regelsuche.demo.DemoRuleSet.rules());
        de.regelsuche.equivalence.SymPyEquivalenceService equivalence =
            new de.regelsuche.equivalence.SymPyEquivalenceService();
        List<java.util.Map<String, Object>> stepReports = new java.util.ArrayList<>();
        List<de.regelsuche.mining.SuccessfulTransformationPath> aggregated = new java.util.ArrayList<>();
        long firstRunSteps = -1, firstRunMillis = -1, lastRunSteps = -1, lastRunMillis = -1;
        boolean usedLearnedRule = false;
        double lastConfidence = 0.0;
        List<Double> confidenceTrace = new java.util.ArrayList<>();
        for (int i = 0; i < expressions.length; i++) {
            String expr = expressions[i];
            long t0 = System.nanoTime();
            String root = canonicalizer.canonicalize(expr);
            de.regelsuche.scoring.ExpressionScore before = scorer.score(root);
            de.regelsuche.search.SearchProfile profile = de.regelsuche.search.SearchProfile.DISCOVERY_PLUS;
            de.regelsuche.search.strategy.SearchProblem problem =
                new de.regelsuche.search.strategy.SearchProblem(
                    root, engine, scorer, canonicalizer, profile.heuristic())
                    .withMemory(searchMemory);
            List<de.regelsuche.search.strategy.SearchState> states =
                profile.newStrategy().search(problem);
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            int stepsOnBest = 0;
            de.regelsuche.search.strategy.SearchState best = null;
            boolean usedLearnedHere = false;
            for (de.regelsuche.search.strategy.SearchState s : states) {
                if (s.depth() == 0 || !equivalence.areEquivalent(root, s.expression())) {
                    continue;
                }
                if (best == null || s.depth() < best.depth()) {
                    best = s;
                }
                aggregated.add(new de.regelsuche.mining.SuccessfulTransformationPath(
                    "macro-" + i + "-d" + s.depth() + "-" + Integer.toHexString(s.canonicalHash().hashCode()),
                    root, s.expression(), s.path(), s.appliedRuleIds(),
                    before, s.score(), true, "macro-demo",
                    java.util.Map.of("variable", "x")));
                for (String rid : s.appliedRuleIds()) {
                    if (rid != null && rid.startsWith("macro_")) {
                        usedLearnedHere = true;
                    }
                }
            }
            if (best != null) {
                stepsOnBest = best.appliedRuleIds().size();
            }
            de.regelsuche.learning.MacroLearningResult learning = learner.learn(aggregated);
            double confidence = learning.touchedRules().isEmpty()
                ? 0.0 : learning.touchedRules().get(0).confidenceScore();
            confidenceTrace.add(confidence);
            lastConfidence = confidence;
            if (i == 0) {
                firstRunSteps = stepsOnBest;
                firstRunMillis = elapsed;
            }
            if (i == expressions.length - 1) {
                lastRunSteps = stepsOnBest;
                lastRunMillis = elapsed;
                usedLearnedRule = usedLearnedHere;
            }
            stepReports.add(java.util.Map.of(
                "index", i,
                "expression", expr,
                "stepCount", stepsOnBest,
                "elapsedMillis", elapsed,
                "confidenceScore", confidence,
                "learnedRulesActive", inventoryRepository.findEnabled().stream()
                    .filter(r -> r.id().startsWith("macro_")).count()
            ));
        }
        de.regelsuche.json.JsonWriter w = new de.regelsuche.json.JsonWriter();
        w.beginObject();
        w.property("id", "macro-learning");
        w.property("title", "System lernt eine Makroregel");
        w.property("usedLearnedRule", usedLearnedRule);
        w.property("finalConfidenceScore", lastConfidence);
        w.array("confidenceTrace", arr -> confidenceTrace.forEach(v -> arr.value(v.toString())));
        final long fSteps = firstRunSteps, fMs = firstRunMillis;
        final long lSteps = lastRunSteps, lMs = lastRunMillis;
        w.object("speedup", s -> {
            s.property("firstRunSteps", fSteps);
            s.property("firstRunMillis", fMs);
            s.property("lastRunSteps", lSteps);
            s.property("lastRunMillis", lMs);
        });
        w.array("steps", arr -> stepReports.forEach(rep -> arr.objectValue(inner -> {
            inner.property("index", (int) rep.get("index"));
            inner.property("expression", (String) rep.get("expression"));
            inner.property("stepCount", (int) rep.get("stepCount"));
            inner.property("elapsedMillis", (long) rep.get("elapsedMillis"));
            inner.property("confidenceScore", (double) rep.get("confidenceScore"));
            inner.property("learnedRulesActive", (long) rep.get("learnedRulesActive"));
        })));
        w.endObject();
        return w.toString();
    }

    private void handleMemory(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring("/api/memory".length()).replaceFirst("^/", "");
        de.regelsuche.json.JsonWriter w = new de.regelsuche.json.JsonWriter();
        switch (suffix) {
            case "states" -> {
                w.beginObject();
                w.property("size", searchMemory.table().size());
                w.array("entries", arr -> searchMemory.table().entries().forEach(e ->
                    arr.objectValue(inner -> {
                        inner.property("canonicalHash", e.canonicalHash());
                        inner.property("canonicalExpression", e.canonicalExpression());
                        inner.property("bestScore", e.bestScore());
                        inner.property("minDepthSeen", e.minDepthSeen());
                        inner.property("bestKnownPathId", e.bestKnownPathId());
                        inner.stringArray("reachedByRuleIds",
                            new java.util.ArrayList<>(e.reachedByRuleIds()));
                        inner.property("visitCount", e.visitCount());
                        inner.property("firstSeen", e.firstSeen().toString());
                        inner.property("lastSeen", e.lastSeen().toString());
                    })));
                w.endObject();
                sendJson(exchange, 200, w.toString());
            }
            case "pruning" -> sendJson(exchange, 200, renderPruningDecisionsJson());
            case "macros" -> {
                w.beginObject();
                w.array("macros", arr -> inventoryRepository.findAll().stream()
                    .filter(r -> r.occurrenceCount() > 0 || r.confidenceScore() > 0)
                    .forEach(r -> arr.objectValue(inner -> {
                        inner.property("id", r.id());
                        inner.property("leftPattern", r.leftPattern());
                        inner.property("rightPattern", r.rightPattern());
                        inner.property("occurrenceCount", r.occurrenceCount());
                        inner.property("confidenceScore", r.confidenceScore());
                        inner.property("averageImprovement", r.averageImprovement());
                        inner.stringArray("supportingPathIds", r.supportingPathIds());
                        inner.property("enabled", inventoryRepository.isEnabled(r.id()));
                    })));
                w.endObject();
                sendJson(exchange, 200, w.toString());
            }
            case "universal" -> {
                // Surfaces the top universal patterns + cross-task rule
                // coverage so the workbench UI can show "the moves that work
                // everywhere" — see GlobalMemoryService for the scoring rule.
                de.regelsuche.search.memory.GlobalMemoryService global =
                    new de.regelsuche.search.memory.GlobalMemoryService(searchMemory.table());
                java.time.Instant now = java.time.Instant.now();
                var topPatterns = global.topUniversalPatterns(20, now);
                java.util.Map<String, Integer> coverage = global.ruleCoverage();
                w.beginObject();
                w.property("size", searchMemory.table().size());
                w.array("patterns", arr -> topPatterns.forEach(entry ->
                    arr.objectValue(inner -> {
                        inner.property("canonicalHash", entry.canonicalHash());
                        inner.property("canonicalExpression", entry.canonicalExpression());
                        inner.property("universalityScore", global.universalityScore(entry, now));
                        inner.property("visitCount", entry.visitCount());
                        inner.property("bestScore", entry.bestScore());
                        inner.property("minDepthSeen", entry.minDepthSeen());
                        inner.property("bestKnownPathId", entry.bestKnownPathId());
                        inner.stringArray("reachedByRuleIds",
                            new java.util.ArrayList<>(entry.reachedByRuleIds()));
                        inner.property("firstSeen", entry.firstSeen().toString());
                        inner.property("lastSeen", entry.lastSeen().toString());
                    })));
                w.array("ruleCoverage", arr -> coverage.forEach((ruleId, count) ->
                    arr.objectValue(inner -> {
                        inner.property("ruleId", ruleId);
                        inner.property("coverage", count);
                    })));
                w.endObject();
                sendJson(exchange, 200, w.toString());
            }
            case "" -> {
                w.beginObject();
                w.property("size", searchMemory.table().size());
                w.property("pruningDecisions", searchMemory.decisions().size());
                w.object("links", l -> {
                    l.property("states", "/api/memory/states");
                    l.property("pruning", "/api/memory/pruning");
                    l.property("macros", "/api/memory/macros");
                    l.property("universal", "/api/memory/universal");
                });
                w.endObject();
                sendJson(exchange, 200, w.toString());
            }
            default -> sendStatus(exchange, 404, "unknown memory endpoint: " + suffix);
        }
    }

    private void handleDemo(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring("/api/demo".length()).replaceFirst("^/", "");
        if (suffix.isEmpty()) {
            // List all available demos.
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writer.array("demos", arr -> de.regelsuche.demo.DemoCatalog.all().values().forEach(demo ->
                arr.objectValue(inner -> {
                    inner.property("id", demo.id());
                    inner.property("title", demo.title());
                    inner.property("description", demo.description());
                    inner.property("expression", demo.expression());
                    inner.property("inputType", demo.inputType().name());
                    inner.property("profile", demo.profile().name());
                    inner.property("expectedHighlight", demo.expectedHighlight());
                    inner.property("expectedResultExpression", demo.expectedResultExpression());
                    inner.property("domain", demo.domain());
                })));
            writer.endObject();
            sendJson(exchange, 200, writer.toString());
            return;
        }
        de.regelsuche.demo.DemoCatalog.Demo demo = de.regelsuche.demo.DemoCatalog.byId(suffix);
        if (demo == null) {
            sendStatus(exchange, 404, "unknown demo: " + suffix);
            return;
        }
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
            sendStatus(exchange, 405, "method not allowed");
            return;
        }
        if ("macro-learning".equals(demo.id()) && "POST".equalsIgnoreCase(method)) {
            sendJson(exchange, 200, runMacroLearningDemo());
            return;
        }
        de.regelsuche.demo.DemoService demoService =
            new de.regelsuche.demo.DemoService(graphStore,
                new de.regelsuche.transform.AstRewriteTransformationEngine(
                    de.regelsuche.demo.DemoRuleSet.rules()),
                searchMemory);
        demoService.useProofBridge(leanProofBridgeService);
        de.regelsuche.demo.DemoService.DemoRunResult result = demoService.run(demo);
        sendJson(exchange, 200, renderDemoBundle(result));
    }

    private String renderDemoBundle(de.regelsuche.demo.DemoService.DemoRunResult result) {
        de.regelsuche.demo.DemoCatalog.Demo demo = result.demo();
        var macros = new de.regelsuche.mining.MacroRuleMiner().mine(graphStore.discoveredTransformations());
        var known = new de.regelsuche.mining.KnownRuleRepository();
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("id", demo.id());
        writer.property("title", demo.title());
        writer.property("description", demo.description());
        writer.property("expression", demo.expression());
        // Stage 4+: ship a LaTeX rendering of the raw user expression
        // alongside the original ASCII string so the front-end demo banner
        // and "Eingabe" cells can typeset it via KaTeX instead of falling
        // back to a hand-styled ASCII fragment with literal `^`, `*`, `/`.
        String expressionLatex =
            de.regelsuche.export.MathPresentation.DEFAULT.latex(demo.expression());
        if (!expressionLatex.isEmpty()) {
            writer.property("expressionLatex", expressionLatex);
        }
        writer.property("inputType", demo.inputType().name());
        writer.property("profile", demo.profile().name());
        writer.property("expectedHighlight", demo.expectedHighlight());
        writer.property("expectedResultExpression", demo.expectedResultExpression());
        writer.property("domain", demo.domain());
        writer.property("expressionType", result.expressionType().name());
        writer.property("comparatorFlipped", result.comparatorFlipped());
        if (!result.inputLatex().isEmpty()) {
            writer.property("inputLatex", result.inputLatex());
        }
        if (!result.resultLatex().isEmpty()) {
            writer.property("resultLatex", result.resultLatex());
        }
        if (result.proofOutcome() != null) {
            writer.object("proofOutcome", po -> {
                var outcome = result.proofOutcome();
                po.property("proofStatus", outcome.candidate().proofStatus().name());
                if (outcome.execution() != null) {
                    po.property("proverStatus", outcome.execution().status().name());
                    po.property("exitCode", outcome.execution().exitCode());
                    po.property("stdout", outcome.execution().stdout());
                    po.property("stderr", outcome.execution().stderr());
                    po.property("elapsedMillis", outcome.execution().durationMillis());
                }
                if (outcome.attempt() != null) {
                    po.property("artifact", outcome.attempt().artifact());
                    po.property("tool", outcome.attempt().tool());
                }
            });
        }
        writer.property("canonicalTargetExpression",
            result.canonicalTargetExpression() == null ? "" : result.canonicalTargetExpression());
        writer.property("rootExpression", result.rootExpression());
        writer.property("targetReached", result.targetReached());
        writer.stringArray("assumptions", result.assumptions());
        writer.object("metrics", m -> {
            m.property("nodes", result.nodesSaved());
            m.property("edges", result.edgesSaved());
            m.property("pathsDiscovered", result.pathsDiscovered());
            m.property("elapsedMillis", result.elapsedMillis());
            m.property("identitiesFound", macros.size());
            m.property("appliedRuleCount", result.appliedRuleIds().size());
        });
        writePath(writer, "selectedPath", result.selectedPath());
        writePath(writer, "bestPath", result.bestPath());
        writePath(writer, "targetPath", result.targetPath());
        writer.array("identities", arr -> macros.forEach(macro ->
            arr.objectValue(inner -> {
                inner.property("id", macro.id());
                inner.property("leftPattern", macro.leftPattern());
                inner.property("rightPattern", macro.rightPattern());
                // Stage 4+: KaTeX-ready LaTeX of the pattern endpoints so
                // the identities list in the demo summary can render the
                // pattern visually instead of as raw `*`/`^` ASCII.
                String leftPatternLatex = de.regelsuche.export.MathPresentation.DEFAULT
                    .latex(macro.leftPattern());
                String rightPatternLatex = de.regelsuche.export.MathPresentation.DEFAULT
                    .latex(macro.rightPattern());
                if (!leftPatternLatex.isEmpty()) {
                    inner.property("leftPatternLatex", leftPatternLatex);
                }
                if (!rightPatternLatex.isEmpty()) {
                    inner.property("rightPatternLatex", rightPatternLatex);
                }
                inner.property("occurrences", macro.occurrences());
                inner.property("compressionRatio", macro.compressionRatio());
                inner.property("proofStatus", macro.proofStatus().name());
                inner.property("knownRuleStatus",
                    known.statusFor(macro.leftPattern(), macro.rightPattern()).name());
                inner.property("domain",
                    new de.regelsuche.mining.MacroDomainInferrer().inferDomain(macro));
            })));
        writer.object("links", l -> {
            l.property("searchGraph", "/api/search-graph");
            l.property("semanticSearchGraph", "/api/search-graph/semantic");
            l.property("paths", "/api/paths");
            l.property("identities", "/api/identities");
            l.property("candidates", "/api/candidates");
            l.property("inventory", "/api/inventory");
            l.property("reportMarkdown", "/api/exports/search-analysis-report.md");
            l.property("reportLatex", "/api/exports/search-analysis-report.tex");
            l.property("reportJson", "/api/exports/search-analysis-report.json");
            l.property("searchGraphMermaid", "/api/exports/search-graph.mmd");
            l.property("searchGraphGraphMl", "/api/exports/search-graph.graphml");
            l.property("reportBundleZip", "/api/exports/bundle.zip");
            l.property("analyzeMove", "/api/analyze/move?expression=" + demo.expression());
        });
        writer.endObject();
        return writer.toString();
    }

    private static void writePath(JsonWriter writer, String key,
                                  de.regelsuche.discovery.DiscoveredTransformation path) {
        if (path == null) {
            writer.nullProperty(key);
            return;
        }
        // Use the central MathPresentation so the LaTeX rendering goes
        // through the AST-based renderer (with safe fallback) rather than
        // raw string concatenation.
        de.regelsuche.export.MathPresentation latex = de.regelsuche.export.MathPresentation.DEFAULT;
        writer.object(key, b -> {
            b.property("id", path.id());
            b.property("originalExpression", path.originalExpression());
            b.property("improvedExpression", path.improvedExpression());
            // Stage 4+: dedicated LaTeX fields for the path endpoints so
            // the front-end can typeset the demo-summary banner and the
            // "Treffer (selectedPath)" row with KaTeX instead of ASCII.
            String originalLatex = latex.latex(path.originalExpression());
            String improvedLatex = latex.latex(path.improvedExpression());
            if (!originalLatex.isEmpty()) {
                b.property("originalExpressionLatex", originalLatex);
            }
            if (!improvedLatex.isEmpty()) {
                b.property("improvedExpressionLatex", improvedLatex);
            }
            b.property("totalImprovement", path.totalImprovement());
            b.property("steps", path.steps().size());
            b.property("proofStatus", path.validationStatus().name());
            b.array("stepDetails", arr -> path.steps().forEach(step ->
                arr.objectValue(s -> {
                    s.property("index", step.index());
                    s.property("beforeExpression", step.beforeExpression());
                    s.property("afterExpression", step.afterExpression());
                    // Stage 4+: matching LaTeX fields per step so the
                    // best-move block and replay views can KaTeX-typeset
                    // before/after expressions without a client-side
                    // ASCII fallback.
                    String beforeLatex = latex.latex(step.beforeExpression());
                    String afterLatex = latex.latex(step.afterExpression());
                    if (!beforeLatex.isEmpty()) {
                        s.property("beforeLatex", beforeLatex);
                    }
                    if (!afterLatex.isEmpty()) {
                        s.property("afterLatex", afterLatex);
                    }
                    s.property("ruleId", step.ruleId());
                    s.property("ruleKind", step.ruleKind().name());
                    s.property("scoreBefore", step.scoreBefore());
                    s.property("scoreAfter", step.scoreAfter());
                    s.property("equivalencePreserving", step.equivalencePreserving());
                })));
        });
    }

    private void handleProofStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendStatus(exchange, 405, "method not allowed");
            return;
        }
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.array("statuses", arr ->
            de.regelsuche.proof.ProofStatusDescription.all().values().forEach(d ->
                arr.objectValue(inner -> {
                    inner.property("status", d.status().name());
                    inner.property("ordinal", d.status().ordinal());
                    inner.property("label", d.label());
                    inner.property("descriptionDe", d.summaryDe());
                    inner.property("descriptionEn", d.summaryEn());
                })));
        writer.endObject();
        sendJson(exchange, 200, writer.toString());
    }

    /**
     * POST /api/proof-bridge — body {@code {"leftPattern": "...", "rightPattern": "...",
     * "assumptions": ["x != 0", ...], "tool": "lean4"|"smt"}}.
     *
     * <p>Runs the configured {@link de.regelsuche.proof.ProofBridgeService}
     * (Lean / SMT) and returns prover status, exit code, stdout, stderr,
     * elapsed time, and the generated proof script so the UI's "Proof prüfen"
     * button can render the full execution result. Only a successful prover
     * run actually promotes the candidate to {@code FORMALLY_PROVED}; without
     * an executor configured we report {@code SCRIPT_GENERATED}.</p>
     */
    private void handleProofBridge(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendStatus(exchange, 405, "method not allowed");
            return;
        }
        Map<String, Object> body = readJsonObject(exchange);
        String left = stringValue(body, "leftPattern", "");
        String right = stringValue(body, "rightPattern", "");
        if (left.isBlank() || right.isBlank()) {
            sendStatus(exchange, 400, "leftPattern and rightPattern are required");
            return;
        }
        String tool = stringValue(body, "tool", "lean4").toLowerCase(java.util.Locale.ROOT);
        de.regelsuche.proof.ProofBridgeService service =
            "smt".equals(tool) ? smtProofBridgeService : leanProofBridgeService;
        List<de.regelsuche.assumption.Assumption> assumptions = new java.util.ArrayList<>();
        Object raw = body.get("assumptions");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    assumptions.add(new de.regelsuche.assumption.Assumption(
                        de.regelsuche.assumption.Assumption.Kind.CUSTOM, item.toString()));
                }
            }
        }
        de.regelsuche.mining.RuleCandidate candidate = new de.regelsuche.mining.RuleCandidate(
            left, right, 1, 1.0, 1, true, true, false,
            List.of(),
            de.regelsuche.mining.RuleStatus.NEW,
            de.regelsuche.validation.CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            Integer.toHexString((left + "->" + right).hashCode()),
            List.of()
        );
        de.regelsuche.proof.ProofBridgeService.ProofAttemptOutcome outcome =
            service.attemptWithDetails(candidate, assumptions);

        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("leftPattern", left);
        writer.property("rightPattern", right);
        writer.property("tool", tool);
        writer.property("proofStatus", outcome.candidate().proofStatus().name());
        if (outcome.execution() != null) {
            writer.property("proverStatus", outcome.execution().status().name());
            writer.property("exitCode", outcome.execution().exitCode());
            writer.property("stdout", outcome.execution().stdout());
            writer.property("stderr", outcome.execution().stderr());
            writer.property("elapsedMillis", outcome.execution().durationMillis());
        } else {
            // No executor configured — be explicit so the UI can show the
            // "script generated only" state instead of leaving the field
            // absent.
            writer.property("proverStatus",
                de.regelsuche.proof.ProverExecutionResult.Status.SCRIPT_GENERATED.name());
            writer.property("exitCode", -1);
            writer.property("stdout", "");
            writer.property("stderr", "");
            writer.property("elapsedMillis", 0L);
        }
        if (outcome.attempt() != null) {
            writer.property("artifact", outcome.attempt().artifact());
            writer.property("artifactTool", outcome.attempt().tool());
        }
        writer.endObject();
        sendJson(exchange, 200, writer.toString());
    }

    // ── Proof Job REST API ─────────────────────────────────────────────────
    //
    // Routes (all under {@code /api/proof/jobs}, registered as a single
    // prefix context):
    //   GET    /api/proof/jobs                              → list all jobs
    //   POST   /api/proof/jobs                              → submit a new job
    //   GET    /api/proof/jobs/{id}                         → job details
    //   POST   /api/proof/jobs/{id}/cancel                  → cancel
    //   GET    /api/proof/jobs/{id}/artifacts               → bundle file list
    //   GET    /api/proof/jobs/{id}/artifacts/{name}        → fetch file body

    private void handleProofJobs(HttpExchange exchange) throws IOException {
        if (proofWorkbenchService == null) {
            sendStatus(exchange, 503, "proof workbench disabled "
                + "(set REGELSUCHE_PROOF_ENABLED=true)");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        // Strip "/api/proof/jobs" prefix and split.
        String suffix = path.substring("/api/proof/jobs".length());
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

        if (suffix.isEmpty() || "/".equals(suffix)) {
            if ("GET".equals(method)) {
                handleProofJobList(exchange);
            } else if ("POST".equals(method)) {
                handleProofJobCreate(exchange);
            } else {
                sendStatus(exchange, 405, "method not allowed");
            }
            return;
        }

        String[] parts = suffix.substring(1).split("/", -1);
        String jobId = parts[0];
        if (parts.length == 1) {
            if ("GET".equals(method)) {
                handleProofJobGet(exchange, jobId);
            } else {
                sendStatus(exchange, 405, "method not allowed");
            }
            return;
        }
        if (parts.length == 2 && "cancel".equals(parts[1])) {
            if ("POST".equals(method)) {
                handleProofJobCancel(exchange, jobId);
            } else {
                sendStatus(exchange, 405, "method not allowed");
            }
            return;
        }
        if (parts.length == 2 && "artifacts".equals(parts[1])) {
            if ("GET".equals(method)) {
                handleProofJobArtifactList(exchange, jobId);
            } else {
                sendStatus(exchange, 405, "method not allowed");
            }
            return;
        }
        if (parts.length == 3 && "artifacts".equals(parts[1])) {
            if ("GET".equals(method)) {
                handleProofJobArtifactRead(exchange, jobId, parts[2]);
            } else {
                sendStatus(exchange, 405, "method not allowed");
            }
            return;
        }
        sendStatus(exchange, 404, "unknown proof endpoint: " + path);
    }

    private void handleProofJobList(HttpExchange exchange) throws IOException {
        var jobs = proofWorkbenchService.listJobs();
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.array("jobs", arr -> jobs.forEach(job ->
            arr.objectValue(o -> writeProofJobJson(o, job))));
        writer.endObject();
        sendJson(exchange, 200, writer.toString());
    }

    private void handleProofJobCreate(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonObject(exchange);
        String left = stringValue(body, "leftPattern", "").trim();
        String right = stringValue(body, "rightPattern", "").trim();
        if (left.isBlank() || right.isBlank()) {
            sendStatus(exchange, 400, "leftPattern and rightPattern are required");
            return;
        }
        int priority = intValue(body, "priority", 0);
        if (priority < 0) {
            sendStatus(exchange, 400, "priority must be >= 0");
            return;
        }
        String worker = stringValue(body, "worker", "").trim();
        if (!worker.isEmpty()) {
            sendStatus(exchange, 400, "worker selection is not supported");
            return;
        }
        List<de.regelsuche.assumption.Assumption> assumptions = new java.util.ArrayList<>();
        Object raw = body.get("assumptions");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                if (item instanceof Map<?, ?> map) {
                    Object exprRaw = map.get("expression");
                    String expr = exprRaw == null ? "" : String.valueOf(exprRaw);
                    Object kindObj = map.get("kind");
                    String kindRaw = kindObj == null ? "CUSTOM" : String.valueOf(kindObj);
                    de.regelsuche.assumption.Assumption.Kind kind;
                    try {
                        kind = de.regelsuche.assumption.Assumption.Kind.valueOf(
                            kindRaw.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ex) {
                        kind = de.regelsuche.assumption.Assumption.Kind.CUSTOM;
                    }
                    assumptions.add(new de.regelsuche.assumption.Assumption(kind, expr));
                } else {
                    assumptions.add(new de.regelsuche.assumption.Assumption(
                        de.regelsuche.assumption.Assumption.Kind.CUSTOM, item.toString()));
                }
            }
        }
        String jobId = proofWorkbenchService.submit(left, right, assumptions, priority);
        var job = proofWorkbenchService.getJob(jobId).orElse(null);
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("jobId", jobId);
        if (job != null) {
            writer.property("status", job.status().name());
            writer.property("workerId", job.workerType());
        }
        writer.endObject();
        sendJson(exchange, 201, writer.toString());
    }

    private void handleProofJobGet(HttpExchange exchange, String jobId) throws IOException {
        var job = proofWorkbenchService.getJob(jobId);
        if (job.isEmpty()) {
            sendStatus(exchange, 404, "job not found: " + jobId);
            return;
        }
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writeProofJobJson(writer, job.get());
        writer.endObject();
        sendJson(exchange, 200, writer.toString());
    }

    private void handleProofJobCancel(HttpExchange exchange, String jobId) throws IOException {
        var cancelled = proofWorkbenchService.cancel(jobId);
        if (cancelled.isEmpty()) {
            sendStatus(exchange, 404, "job not found: " + jobId);
            return;
        }
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writeProofJobJson(writer, cancelled.get());
        writer.endObject();
        sendJson(exchange, 200, writer.toString());
    }

    private void handleProofJobArtifactList(HttpExchange exchange, String jobId) throws IOException {
        if (proofWorkbenchService.getJob(jobId).isEmpty()) {
            sendStatus(exchange, 404, "job not found: " + jobId);
            return;
        }
        var names = proofWorkbenchService.listArtifacts(jobId);
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("jobId", jobId);
        writer.array("artifacts", arr -> names.forEach(arr::value));
        writer.endObject();
        sendJson(exchange, 200, writer.toString());
    }

    private void handleProofJobArtifactRead(HttpExchange exchange, String jobId, String name) throws IOException {
        // Defence-in-depth: refuse traversal even though the artifact repo
        // already validates ids.
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            sendStatus(exchange, 400, "invalid artifact name");
            return;
        }
        var body = proofWorkbenchService.readArtifact(jobId, name);
        if (body.isEmpty()) {
            sendStatus(exchange, 404, "artifact not found: " + jobId + "/" + name);
            return;
        }
        String contentType = "text/plain; charset=utf-8";
        if (name.endsWith(".json")) {
            contentType = "application/json; charset=utf-8";
        }
        send(exchange, 200, contentType, body.get());
    }

    private void writeProofJobJson(JsonWriter writer, de.regelsuche.proof.ProofJob job) {
        writer.property("id", job.id());
        writer.property("leftPattern", job.leftPattern());
        writer.property("rightPattern", job.rightPattern());
        writer.property("status", job.status().name());
        writer.property("priority", job.priority());
        writer.property("retryCount", job.retryCount());
        writer.property("maxRetries", job.maxRetries());
        writer.property("workerId", job.workerType());
        writer.property("createdAt", job.createdAt().toString());
        writer.property("updatedAt", job.updatedAt().toString());
        writer.property("proofStatus", job.resultStatus() == null ? "" : job.resultStatus().name());
        writer.property("errorMessage", job.errorMessage());
        writer.array("assumptions", arr -> job.assumptions().forEach(a ->
            arr.objectValue(o -> {
                o.property("kind", a.kind().name());
                o.property("expression", a.expression());
            })));
    }

    private void handleBenchmark(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendStatus(exchange, 405, "method not allowed");
            return;
        }
        long started = System.nanoTime();
        var suite = new de.regelsuche.benchmark.BenchmarkSuite();
        List<de.regelsuche.benchmark.BenchmarkScenarioResult> results = suite.runAll();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("elapsedMillis", elapsedMillis);
        writer.array("scenarios", scenarios -> results.forEach(scenario ->
            scenarios.objectValue(s -> {
                s.property("name", scenario.name());
                s.array("results", rows -> scenario.results().forEach(row ->
                    rows.objectValue(r -> {
                        r.property("strategy", row.strategyName());
                        r.property("expression", row.expression());
                        r.property("exploredStates", row.exploredStates());
                        r.property("visitedStates", row.visitedStates());
                        r.property("bestImprovement", row.bestImprovement());
                        r.property("shortestImprovingDepth", row.shortestImprovingDepth());
                        r.property("expandedSteps", row.expandedSteps());
                        r.property("distinctRules", row.distinctRules());
                        r.property("elapsedMillis", row.elapsedMillis());
                        r.property("proofStatus", row.proofStatus().name());
                        r.property("found", row.found());
                        // Quality metrics ("Ampelstatus" surface).
                        if (row.expectedResultMatched() != null) {
                            r.property("expectedResultMatched", row.expectedResultMatched());
                        } else {
                            r.nullProperty("expectedResultMatched");
                        }
                        r.property("prunedStates", row.prunedStates());
                        r.property("eGraphClasses", row.eGraphClasses());
                        r.property("eGraphNodes", row.eGraphNodes());
                        r.property("classesScanned", row.classesScanned());
                        r.property("nodesScanned", row.nodesScanned());
                        r.property("candidateClassesSkipped", row.candidateClassesSkipped());
                        r.property("matchesFound", row.matchesFound());
                        r.property("matcherCacheHits", row.matcherCacheHits());
                        r.property("matcherCacheMisses", row.matcherCacheMisses());
                        r.property("saturationIterations", row.saturationIterations());
                        r.property("rulesFired", row.rulesFired());
                        r.property("saturationSavings", row.saturationSavings());
                        r.property("learnedRuleUsed", row.learnedRuleUsed());
                        r.property("exportBundleValid", row.exportBundleValid());
                        r.property("quality", row.qualityLabel());
                    })));
            })));
        writer.endObject();
        sendJson(exchange, 200, writer.toString());
    }

    private byte[] buildReportBundleZip() throws IOException {
        var report = new de.regelsuche.export.SearchAnalysisReportService();
        var ctx = analysisReportContext();
        var transformations = graphStore.discoveredTransformations();
        var graph = buildSearchGraph();
        var semanticGraph = buildSemanticSearchGraph(
            de.regelsuche.api.searchgraph.semantic.SemanticGraphViewMode.SEMANTIC,
            de.regelsuche.api.searchgraph.semantic.SemanticMacroStepDisplay.COMPACT,
            false,
            true,
            false,
            12,
            8,
            ""
        );
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            putZipEntry(zip, "search-analysis-report.md", report.renderMarkdown(ctx));
            putZipEntry(zip, "search-analysis-report.tex", report.renderLatex(ctx));
            putZipEntry(zip, "search-analysis-report.json", report.renderJson(ctx));
            putZipEntry(zip, "transformations.md", exportService.exportMarkdown(transformations));
            putZipEntry(zip, "transformations.tex", exportService.exportLatex(transformations));
            putZipEntry(zip, "transformations.json", exportService.exportBundle(exportQuery.bundle()));
            putZipEntry(zip, "search-graph.mmd", exportService.exportSearchGraphMermaid(graph));
            putZipEntry(zip, "search-graph.graphml", exportService.exportSearchGraphGraphMl(graph));
            putZipEntry(zip, "search-graph.json", exportService.exportSearchGraphJson(graph));
            putZipEntry(zip, "search-graph-semantic.mmd",
                de.regelsuche.api.searchgraph.semantic.SemanticSearchGraphJsonSerializer.toMermaid(semanticGraph));
            putZipEntry(zip, "search-graph-semantic.json",
                de.regelsuche.api.searchgraph.semantic.SemanticSearchGraphJsonSerializer.toJson(semanticGraph));
            putZipEntry(zip, "best-path.md", exportService.exportBestPathMarkdown(transformations));
            putZipEntry(zip, "rule-inventory.json",
                exportService.exportJson(List.of(), List.of(), inventoryRepository.findAll()));
            putZipEntry(zip, "pruning-decisions.json", renderPruningDecisionsJson());
        }
        return out.toByteArray();
    }

    private String renderPruningDecisionsJson() {
        de.regelsuche.json.JsonWriter w = new de.regelsuche.json.JsonWriter();
        w.beginObject();
        w.property("count", searchMemory.decisions().size());
        w.array("decisions", arr -> searchMemory.decisions().forEach(d ->
            arr.objectValue(inner -> {
                inner.property("expression", d.expression());
                inner.property("canonicalHash", d.canonicalHash());
                inner.property("reason", d.reason().name());
                inner.property("explanation", d.explanation());
            })));
        w.endObject();
        return w.toString();
    }

    private static void putZipEntry(java.util.zip.ZipOutputStream zip, String name, String content)
        throws IOException {
        java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(name);
        zip.putNextEntry(entry);
        if (content != null) {
            zip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        zip.closeEntry();
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.contains("..")) {
            sendStatus(exchange, 400, "bad request");
            return;
        }
        if ("/".equals(path) || "/index.html".equals(path)) {
            sendStaticResource(exchange, "/web/index.html", "text/html; charset=utf-8");
        } else if (path.startsWith("/static/")) {
            String resource = "/web" + path.substring("/static".length());
            sendStaticResource(exchange, resource, mimeFor(resource));
        } else if (path.startsWith("/vendor/")
            || path.equals("/app.js")
            || path.equals("/style.css")
            || path.equals("/rule-radar.js")
            || path.equals("/rule-radar.css")) {
            sendStaticResource(exchange, "/web" + path, mimeFor(path));
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
        if (resource.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (resource.endsWith(".woff")) {
            return "font/woff";
        }
        if (resource.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (resource.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (resource.endsWith(".json") || resource.endsWith(".map")) {
            return "application/json; charset=utf-8";
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
                String rawValue = part.substring(idx + 1);
                String decoded;
                try {
                    decoded = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ex) {
                    decoded = rawValue;
                }
                parsed.put(part.substring(0, idx), decoded);
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
