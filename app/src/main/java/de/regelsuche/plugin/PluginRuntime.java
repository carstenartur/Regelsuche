package de.regelsuche.plugin;

import de.regelsuche.mining.RulePatternParser;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Logger;

public final class PluginRuntime implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(PluginRuntime.class.getName());

    private final PluginRuntimeConfig config;
    private final RuleFileLoader ruleFileLoader = new RuleFileLoader();
    private RuleRegistry ruleRegistry = new RuleRegistry();
    private TransformationRegistry transformationRegistry = new TransformationRegistry();
    private AstVisitorRegistry astVisitorRegistry = new AstVisitorRegistry();
    private MacroRegistry macroRegistry = new MacroRegistry();
    private SearchStrategyRegistry searchStrategyRegistry = new SearchStrategyRegistry();
    private HeuristicRegistry heuristicRegistry = new HeuristicRegistry();
    private CostFunctionRegistry costFunctionRegistry = new CostFunctionRegistry();
    private RendererRegistry rendererRegistry = new RendererRegistry();
    private ExplanationRegistry explanationRegistry = new ExplanationRegistry();
    private ParserExtensionRegistry parserExtensionRegistry = new ParserExtensionRegistry();
    private ExampleRegistry exampleRegistry = new ExampleRegistry();
    private List<LoadedPlugin> loadedPlugins = List.of();
    private List<RuntimeDiagnostic> diagnostics = List.of();
    private List<PatternTransformation> macroTransformations = List.of();
    private List<RuleConflictDetector.RuleConflict> conflicts = List.of();
    private List<RuleConflictDetector.CyclicConflict> cyclicConflicts = List.of();
    private List<RuleProfile> profiles = List.of();
    private List<LoadedRuleFile> loadedRuleFiles = List.of();
    private URLClassLoader externalPluginClassLoader;

    public PluginRuntime() {
        this(PluginRuntimeConfig.defaults());
    }

    public PluginRuntime(PluginRuntimeConfig config) {
        this.config = config;
        reload();
    }

    public void reload() {
        closeQuietly();
        this.ruleRegistry = new RuleRegistry();
        this.transformationRegistry = new TransformationRegistry();
        this.astVisitorRegistry = new AstVisitorRegistry();
        this.macroRegistry = new MacroRegistry();
        this.searchStrategyRegistry = new SearchStrategyRegistry();
        this.heuristicRegistry = new HeuristicRegistry();
        this.costFunctionRegistry = new CostFunctionRegistry();
        this.rendererRegistry = new RendererRegistry();
        this.explanationRegistry = new ExplanationRegistry();
        this.parserExtensionRegistry = new ParserExtensionRegistry();
        this.exampleRegistry = new ExampleRegistry();
        List<LoadedPlugin> discoveredPlugins = new ArrayList<>();
        List<RuntimeDiagnostic> discoveredDiagnostics = new ArrayList<>();
        if (config.loadClasspathPlugins()) {
            loadPlugins(ServiceLoader.load(RegelsuchePlugin.class), "classpath", discoveredPlugins, discoveredDiagnostics);
        }
        loadExternalPlugins(discoveredPlugins, discoveredDiagnostics);
        this.profiles = List.of();
        List<LoadedRuleFile> discoveredRuleFiles = new ArrayList<>();
        loadRuleFiles(discoveredDiagnostics, discoveredRuleFiles);
        disableConfiguredRules();
        applyActiveProfile(discoveredDiagnostics);
        this.macroTransformations = buildMacroTransformations(discoveredDiagnostics);
        List<RuleConflictDetector.ConflictCandidate> conflictCandidates = buildConflictCandidates();
        this.conflicts = RuleConflictDetector.detect(conflictCandidates);
        for (RuleConflictDetector.RuleConflict conflict : conflicts) {
            discoveredDiagnostics.add(new RuntimeDiagnostic(
                "rule-conflict",
                "Competing rules share the same source pattern: " + String.join(", ", conflict.ruleIds())
            ));
        }
        this.cyclicConflicts = RuleConflictDetector.detectCycles(conflictCandidates);
        for (RuleConflictDetector.CyclicConflict cycle : cyclicConflicts) {
            discoveredDiagnostics.add(new RuntimeDiagnostic(
                "rule-cycle",
                "Inverse rules can loop indefinitely: " + String.join(", ", cycle.ruleIds())
            ));
        }
        loadedPlugins = List.copyOf(discoveredPlugins);
        loadedRuleFiles = List.copyOf(discoveredRuleFiles);
        diagnostics = List.copyOf(discoveredDiagnostics);
    }

    public RuleRegistry ruleRegistry() {
        return ruleRegistry;
    }

    public TransformationRegistry transformationRegistry() {
        return transformationRegistry;
    }

    public AstVisitorRegistry astVisitorRegistry() {
        return astVisitorRegistry;
    }

    public MacroRegistry macroRegistry() {
        return macroRegistry;
    }

    public SearchStrategyRegistry searchStrategyRegistry() {
        return searchStrategyRegistry;
    }

    public HeuristicRegistry heuristicRegistry() {
        return heuristicRegistry;
    }

    public CostFunctionRegistry costFunctionRegistry() {
        return costFunctionRegistry;
    }

    public RendererRegistry rendererRegistry() {
        return rendererRegistry;
    }

    public ExplanationRegistry explanationRegistry() {
        return explanationRegistry;
    }

    public ParserExtensionRegistry parserExtensionRegistry() {
        return parserExtensionRegistry;
    }

    public ExampleRegistry exampleRegistry() {
        return exampleRegistry;
    }

    public List<LoadedPlugin> loadedPlugins() {
        return loadedPlugins;
    }

    public List<RuntimeDiagnostic> diagnostics() {
        return diagnostics;
    }

    public PluginAwareAstRewriteTransformationEngine createTransformationEngine() {
        List<RewriteRule> combined = new ArrayList<>(de.regelsuche.transform.AstRewriteTransformationEngine.defaultRules());
        combined.addAll(ruleRegistry.enabledRules());
        combined.addAll(transformationRegistry.enabledTransformations());
        combined.addAll(macroTransformations);
        return new PluginAwareAstRewriteTransformationEngine(combined, astVisitorRegistry);
    }

    public List<PatternTransformation> macroTransformations() {
        return macroTransformations;
    }

    public List<RuleConflictDetector.RuleConflict> conflicts() {
        return conflicts;
    }

    public List<RuleConflictDetector.CyclicConflict> cyclicConflicts() {
        return cyclicConflicts;
    }

    public List<RuleProfile> profiles() {
        return profiles;
    }

    public List<LoadedRuleFile> loadedRuleFiles() {
        return loadedRuleFiles;
    }

    public List<RegisteredRuleView> registeredRules() {
        Map<String, RegisteredRuleView> all = new LinkedHashMap<>();
        for (RuleRegistry.RuleRegistration registration : ruleRegistry.registrations()) {
            all.put(registration.id(), new RegisteredRuleView(
                registration.id(),
                registration.source(),
                registration.explanation(),
                registration.tags(),
                registration.conditions(),
                registration.enabled(),
                "rule"
            ));
        }
        for (TransformationRegistry.TransformationRegistration registration : transformationRegistry.registrations()) {
            all.put(registration.id(), new RegisteredRuleView(
                registration.id(),
                registration.source(),
                registration.explanation(),
                registration.tags(),
                List.of(),
                registration.enabled(),
                "transformation"
            ));
        }
        return List.copyOf(all.values());
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void loadExternalPlugins(List<LoadedPlugin> discoveredPlugins, List<RuntimeDiagnostic> discoveredDiagnostics) {
        try {
            if (!Files.isDirectory(config.pluginsDirectory())) {
                return;
            }
            List<URL> urls;
            try (var stream = Files.list(config.pluginsDirectory())) {
                urls = stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(this::toUrl)
                    .toList();
            }
            if (urls.isEmpty()) {
                return;
            }
            externalPluginClassLoader = new URLClassLoader(urls.toArray(URL[]::new),
                Thread.currentThread().getContextClassLoader());
            loadPlugins(ServiceLoader.load(RegelsuchePlugin.class, externalPluginClassLoader),
                config.pluginsDirectory().toString(), discoveredPlugins, discoveredDiagnostics);
        } catch (IOException ex) {
            discoveredDiagnostics.add(new RuntimeDiagnostic(
                "plugin-runtime",
                "Could not scan plugin directory " + config.pluginsDirectory() + ": " + ex.getMessage()
            ));
        }
    }

    private void loadPlugins(
        ServiceLoader<RegelsuchePlugin> loader,
        String source,
        List<LoadedPlugin> discoveredPlugins,
        List<RuntimeDiagnostic> discoveredDiagnostics
    ) {
        try {
            for (RegelsuchePlugin plugin : loader) {
                boolean enabled = !config.disabledPluginIds().contains(plugin.id());
                discoveredPlugins.add(new LoadedPlugin(plugin.id(), plugin.name(), plugin.version(), source, enabled));
                if (!enabled) {
                    discoveredDiagnostics.add(new RuntimeDiagnostic(plugin.id(), "Plugin disabled by configuration"));
                    continue;
                }
                registerPlugin(plugin, source, discoveredDiagnostics);
            }
        } catch (ServiceConfigurationError error) {
            discoveredDiagnostics.add(new RuntimeDiagnostic(
                "plugin-runtime",
                "Failed to instantiate plugin from " + source + ": " + error.getMessage()
            ));
        }
    }

    private void registerPlugin(RegelsuchePlugin plugin, String source, List<RuntimeDiagnostic> discoveredDiagnostics) {
        try {
            plugin.registerRules(ruleRegistry);
            plugin.registerTransformations(transformationRegistry);
            plugin.registerVisitors(astVisitorRegistry);
            plugin.registerMacros(macroRegistry);
            plugin.registerSearchStrategies(searchStrategyRegistry);
            plugin.registerHeuristics(heuristicRegistry);
            plugin.registerCostFunctions(costFunctionRegistry);
            plugin.registerRenderers(rendererRegistry);
            plugin.registerExplanations(explanationRegistry);
            plugin.registerParserExtensions(parserExtensionRegistry);
            plugin.registerExamples(exampleRegistry);
        } catch (RuntimeException ex) {
            LOGGER.warning(() -> "Plugin " + plugin.id() + " failed: " + ex.getMessage());
            discoveredDiagnostics.add(new RuntimeDiagnostic(plugin.id(),
                "Failed to register plugin contributions from " + source + ": " + ex.getMessage()));
        }
    }

    private void loadRuleFiles(List<RuntimeDiagnostic> discoveredDiagnostics, List<LoadedRuleFile> discoveredRuleFiles) {
        if (!Files.isDirectory(config.rulesDirectory())) {
            return;
        }
        List<Path> files;
        try (var stream = Files.list(config.rulesDirectory())) {
            files = stream
                .filter(Files::isRegularFile)
                .filter(this::isRuleFile)
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        } catch (IOException ex) {
            discoveredDiagnostics.add(new RuntimeDiagnostic(
                "rule-runtime",
                "Could not scan rule directory " + config.rulesDirectory() + ": " + ex.getMessage()
            ));
            return;
        }
        for (Path file : files) {
            try {
                RuleFileLoadResult result = ruleFileLoader.load(file, ruleRegistry, macroRegistry);
                List<String> fileDiagnostics = result.diagnostics().stream()
                    .map(RuleFileParser.RuleFileDiagnostic::format)
                    .toList();
                discoveredRuleFiles.add(new LoadedRuleFile(file.toString(), result.loadedEntries(), true, fileDiagnostics));
                if (!result.profiles().isEmpty()) {
                    List<RuleProfile> merged = new ArrayList<>(this.profiles);
                    merged.addAll(result.profiles());
                    this.profiles = List.copyOf(merged);
                }
                for (RuleFileParser.RuleFileDiagnostic diagnostic : result.diagnostics()) {
                    discoveredDiagnostics.add(new RuntimeDiagnostic(file.toString(), diagnostic.format()));
                }
            } catch (RuleFileParseException ex) {
                List<String> fileDiagnostics = ex.diagnostics().stream()
                    .map(RuleFileParser.RuleFileDiagnostic::format)
                    .toList();
                discoveredRuleFiles.add(new LoadedRuleFile(file.toString(), 0, false, fileDiagnostics));
                for (RuleFileParser.RuleFileDiagnostic diagnostic : ex.diagnostics()) {
                    discoveredDiagnostics.add(new RuntimeDiagnostic(file.toString(), diagnostic.format()));
                }
            } catch (RuntimeException ex) {
                discoveredRuleFiles.add(new LoadedRuleFile(file.toString(), 0, false,
                    List.of("ERROR " + file.getFileName() + ":0 - " + ex.getMessage())));
                discoveredDiagnostics.add(new RuntimeDiagnostic(
                    file.toString(),
                    "Failed to load rule file: " + ex.getMessage()
                ));
            }
        }
    }

    private void disableConfiguredRules() {
        for (String disabledRuleId : config.disabledRuleIds()) {
            ruleRegistry.disable(disabledRuleId);
            // Also disable .forward/.backward variants generated for `direction: both` rules
            ruleRegistry.disable(disabledRuleId + ".forward");
            ruleRegistry.disable(disabledRuleId + ".backward");
            transformationRegistry.disable(disabledRuleId);
            // Macro edges are named "macro.<id>" but the registry stores them as "<id>"
            String macroId = disabledRuleId.startsWith("macro.")
                ? disabledRuleId.substring("macro.".length())
                : disabledRuleId;
            macroRegistry.disable(macroId);
            searchStrategyRegistry.disable(disabledRuleId);
            heuristicRegistry.disable(disabledRuleId);
            costFunctionRegistry.disable(disabledRuleId);
            rendererRegistry.disable(disabledRuleId);
            explanationRegistry.disable(disabledRuleId);
            parserExtensionRegistry.disable(disabledRuleId);
            exampleRegistry.disable(disabledRuleId);
        }
    }

    private void applyActiveProfile(List<RuntimeDiagnostic> discoveredDiagnostics) {
        String activeProfile = config.activeProfile();
        if (activeProfile == null) {
            return;
        }
        RuleProfile profile = profiles.stream()
            .filter(candidate -> candidate.id().equals(activeProfile))
            .findFirst()
            .orElse(null);
        if (profile == null) {
            discoveredDiagnostics.add(new RuntimeDiagnostic(
                "profile:" + activeProfile,
                "Unknown activation profile '" + activeProfile + "'"
            ));
            return;
        }
        for (RuleRegistry.RuleRegistration registration : ruleRegistry.registrations()) {
            if (registration.enabled() && !profile.includes(registration.tags())) {
                ruleRegistry.disable(registration.id());
            }
        }
        for (TransformationRegistry.TransformationRegistration registration : transformationRegistry.registrations()) {
            if (registration.enabled() && !profile.includes(registration.tags())) {
                transformationRegistry.disable(registration.id());
            }
        }
        for (MacroRegistry.MacroRegistration registration : macroRegistry.registrations()) {
            if (registration.enabled() && !profile.includes(registration.macro().tags())) {
                macroRegistry.disable(registration.id());
            }
        }
        discoveredDiagnostics.add(new RuntimeDiagnostic(
            "profile:" + activeProfile,
            "Activation profile '" + activeProfile + "' applied"
        ));
    }

    private List<PatternTransformation> buildMacroTransformations(List<RuntimeDiagnostic> discoveredDiagnostics) {
        RulePatternParser patternParser = new RulePatternParser();
        List<PatternTransformation> built = new ArrayList<>();
        for (RuleMacro macro : macroRegistry.enabledMacros()) {
            try {
                PatternExpr source = PatternExprMapper.toPatternExpr(patternParser.parse(macro.input()));
                PatternExpr target = PatternExprMapper.toPatternExpr(patternParser.parse(macro.output()));
                built.add(new PatternBasedTransformation(
                    "macro." + macro.id(),
                    source,
                    target,
                    RewriteKind.NORMALIZE,
                    false,
                    -macro.priority(),
                    true,
                    macro.explanation()
                ));
            } catch (RuntimeException ex) {
                discoveredDiagnostics.add(new RuntimeDiagnostic(
                    "macro:" + macro.id(),
                    "Could not compile macro into a transformation: " + ex.getMessage()
                ));
            }
        }
        return List.copyOf(built);
    }

    private List<RuleConflictDetector.ConflictCandidate> buildConflictCandidates() {
        List<RuleConflictDetector.ConflictCandidate> candidates = new ArrayList<>();
        for (RewriteRule rule : ruleRegistry.enabledRules()) {
            candidates.add(new RuleConflictDetector.ConflictCandidate(rule.id(), rule));
        }
        for (PatternTransformation transformation : transformationRegistry.enabledTransformations()) {
            candidates.add(new RuleConflictDetector.ConflictCandidate(transformation.id(), transformation));
        }
        for (PatternTransformation macroTransformation : macroTransformations) {
            candidates.add(new RuleConflictDetector.ConflictCandidate(macroTransformation.id(), macroTransformation));
        }
        return candidates;
    }

    private boolean isRuleFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".regelsuche") || name.endsWith(".rules");
    }

    private URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid plugin path: " + path, ex);
        }
    }

    private void closeQuietly() {
        if (externalPluginClassLoader != null) {
            try {
                externalPluginClassLoader.close();
            } catch (IOException ignored) {
                // ignore close noise during reload
            }
            externalPluginClassLoader = null;
        }
    }

    public record LoadedPlugin(String id, String name, String version, String source, boolean enabled) {
    }

    public record RuntimeDiagnostic(String source, String message) {
    }

    public record LoadedRuleFile(String path, int loadedEntries, boolean loaded, List<String> diagnostics) {
        public LoadedRuleFile {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record RegisteredRuleView(
        String id,
        String source,
        String explanation,
        List<String> tags,
        List<RuleFileParser.RuleCondition> conditions,
        boolean enabled,
        String type
    ) {
        public RegisteredRuleView {
            tags = List.copyOf(tags);
            conditions = List.copyOf(conditions);
        }
    }

    public static final class RuleFileLoader {
        private final RuleFileParser parser = new RuleFileParser();
        private final RulePatternParser patternParser = new RulePatternParser();

        public RuleFileLoadResult load(Path file, RuleRegistry ruleRegistry, MacroRegistry macroRegistry) {
            RuleFileParser.RulePackage rulePackage = parser.parse(file);
            if (rulePackage.hasErrors()) {
                throw new RuleFileParseException(rulePackage.diagnostics());
            }
            List<RuleProfile> profiles = new ArrayList<>();
            for (RuleFileParser.Entry entry : rulePackage.entries()) {
                if (entry instanceof RuleFileParser.RuleDefinition rule) {
                    registerRule(file, ruleRegistry, rule);
                } else if (entry instanceof RuleFileParser.MacroDefinition macro) {
                    macroRegistry.register(new RuleMacro(
                        macro.id(),
                        macro.input(),
                        macro.output(),
                        macro.explanation(),
                        macro.tags(),
                        macro.priority(),
                        macro.difficulty()
                    ), file.toString());
                } else if (entry instanceof RuleFileParser.ProfileDefinition profile) {
                    profiles.add(new RuleProfile(
                        profile.id(),
                        profile.enableTags(),
                        profile.disableTags(),
                        file.toString()
                    ));
                }
            }
            return new RuleFileLoadResult(rulePackage.entries().size(), rulePackage.diagnostics(), profiles);
        }

        private void registerRule(Path file, RuleRegistry ruleRegistry, RuleFileParser.RuleDefinition rule) {
            PatternExpr source = PatternExprMapper.toPatternExpr(patternParser.parse(rule.pattern()));
            PatternExpr target = PatternExprMapper.toPatternExpr(patternParser.parse(rule.replace()));
            String explanation = rule.explanation();
            int costDelta = costDeltaForPriority(rule.priority());
            if (rule.direction() == RuleFileParser.RuleDirection.BOTH) {
                ruleRegistry.register(new PatternRewriteRule(
                    rule.id() + ".forward", source, target, RewriteKind.NORMALIZE, false, costDelta, true
                ), file.toString(), explanation, rule.tags(), rule.conditions());
                ruleRegistry.register(new PatternRewriteRule(
                    rule.id() + ".backward", target, source, RewriteKind.NORMALIZE, false, costDelta, true
                ), file.toString(), explanation, rule.tags(), rule.conditions());
            } else if (rule.direction() == RuleFileParser.RuleDirection.BACKWARD) {
                ruleRegistry.register(new PatternRewriteRule(
                    rule.id(), target, source, RewriteKind.NORMALIZE, false, costDelta, true
                ), file.toString(), explanation, rule.tags(), rule.conditions());
            } else {
                ruleRegistry.register(new PatternRewriteRule(
                    rule.id(), source, target, RewriteKind.NORMALIZE, false, costDelta, true
                ), file.toString(), explanation, rule.tags(), rule.conditions());
            }
        }

        private int costDeltaForPriority(int priority) {
            return -priority;
        }
    }

    public record RuleFileLoadResult(
        int loadedEntries,
        List<RuleFileParser.RuleFileDiagnostic> diagnostics,
        List<RuleProfile> profiles
    ) {
        public RuleFileLoadResult {
            diagnostics = List.copyOf(diagnostics);
            profiles = List.copyOf(profiles);
        }
    }
}
