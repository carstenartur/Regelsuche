package de.regelsuche.cli;

import de.regelsuche.cli.core.CliCommandRegistry;
import de.regelsuche.cli.core.CliOptions;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.example.AlgebraicExampleGenerator;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.export.ExportFileService;
import de.regelsuche.export.TransformationExportService;
import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.inventory.RuleInventoryRepository;
import de.regelsuche.mining.DiscoverySettings;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleCandidateListener;
import de.regelsuche.mining.RuleCandidateMiner;
import de.regelsuche.mining.RuleDiscoveryService;
import de.regelsuche.notify.ConsoleNotifier;
import de.regelsuche.plugin.PluginRuntime;
import de.regelsuche.plugin.PluginRuntimeConfig;
import de.regelsuche.plugin.PluginDirectoryWatcher;
import de.regelsuche.plugin.PluginExtensionRegistry;
import de.regelsuche.plugin.PluginReloadResult;
import de.regelsuche.plugin.RuleDebugReport;
import de.regelsuche.plugin.RuleFileParseException;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.TransformationSearchService;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.SymPyTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Dispatches CLI subcommands ({@code discover}, {@code transform},
 * {@code inventory list/export}, {@code path show}) used by the
 * {@link de.regelsuche.App} entry point.
 */
public class CliRouter {
    private final PrintStream out;
    private final ExportFileService exportFileService;
    private final TransformationExportService exportService;
    private final ExpressionGraphStore graphStore;
    private final RuleInventoryRepository inventoryRepository;
    private final boolean ownsResources;

    public CliRouter() {
        this(
            System.out,
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            true
        );
    }

    public CliRouter(
        PrintStream out,
        ExpressionGraphStore graphStore,
        RuleInventoryRepository inventoryRepository,
        TransformationExportService exportService,
        boolean ownsResources
    ) {
        this.out = out;
        this.graphStore = graphStore;
        this.inventoryRepository = inventoryRepository;
        this.exportService = exportService;
        this.exportFileService = new ExportFileService(exportService);
        this.ownsResources = ownsResources;
    }

    public static boolean isSubcommand(String token) {
        return CliCommandRegistry.defaults().contains(token);
    }

    public int run(String[] args) {
        try {
            String command = args[0].toLowerCase(Locale.ROOT);
            String[] rest = Arrays.copyOfRange(args, 1, args.length);
            return switch (command) {
                case "discover" -> runDiscover(rest);
                case "transform" -> runTransform(rest);
                case "inventory" -> runInventory(rest);
                case "path" -> runPath(rest);
                case "benchmark" -> runBenchmark(rest);
                case "serve" -> runServe(rest);
                case "explain" -> runExplain(rest);
                case "plugins" -> runPlugins(rest);
                case "rules" -> runRules(rest);
                default -> {
                    out.println("Unknown command: " + command);
                    yield 1;
                }
            };
        } finally {
            if (ownsResources) {
                close(graphStore);
                close(inventoryRepository);
            }
        }
    }

    private int runDiscover(String[] args) {
        CliOptions options = CliOptions.parse(args);
        int min = Integer.parseInt(options.getOrDefault("min", "1"));
        int max = Integer.parseInt(options.getOrDefault("max", "3"));
        List<String> formats = options.csv("export");
        String directory = options.getOrDefault("dir", ExportFileService.DEFAULT_DIRECTORY);

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
            out.println("Found " + candidates.size() + " rule candidate(s).");
            List<DiscoveredTransformation> transformations = graphStore.discoveredTransformations();
            transformations.stream()
                .max((a, b) -> Integer.compare(a.totalImprovement(), b.totalImprovement()))
                .ifPresent(best -> out.println("Best improvement: "
                    + best.originalExpression() + " -> " + best.improvedExpression()
                    + " (Δ=" + best.totalImprovement() + ")"));
            if (candidates.isEmpty() && transformations.isEmpty()) {
                out.println("Hinweis: Keine Regel und keine Verbesserung gefunden. "
                    + "Bereich (--min/--max) oder Suchtiefe erhöhen.");
            }

            if (!formats.isEmpty()) {
                try {
                    List<Path> written = exportFileService.writeAll(
                        Paths.get(directory),
                        formats,
                        transformations,
                        candidates,
                        inventoryRepository.findAll()
                    );
                    for (Path path : written) {
                        out.println("Exported " + transformations.size()
                            + " transformations to " + path.toAbsolutePath());
                    }
                } catch (Exception ex) {
                    out.println("Export failed: " + ex.getMessage());
                    return 2;
                }
            }
            return 0;
        } finally {
            discovery.shutdown();
        }
    }

    private int runTransform(String[] args) {
        if (args.length == 0) {
            out.println("Usage: transform <expression>");
            return 1;
        }
        String expression = String.join(" ", args);
        try (PluginRuntime runtime = new PluginRuntime(PluginRuntimeConfig.defaults())) {
            TransformationEngine pluginEngine = runtime.createTransformationEngine();
            TransformationEngine symPyEngine = new SymPyTransformationEngine();
            TransformationEngine engine = expr -> {
                LinkedHashSet<Transformation> combined = new LinkedHashSet<>(symPyEngine.transform(expr));
                combined.addAll(pluginEngine.transform(expr));
                return new ArrayList<>(combined);
            };
            TransformationSearchService service = new TransformationSearchService(
                engine,
                graphStore,
                new SearchHeuristic(5, 500, 2),
                new ConsoleNotifier()
            );
            service.submit(new InputRequest(InputType.TERM, expression)).join();
            service.getBestSolution().ifPresentOrElse(
                best -> out.println("Best simplification: " + best.simplifiedExpression()),
                () -> out.println("No simplification found yet")
            );
            service.shutdown();
            return 0;
        }
    }

    private int runPlugins(String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        CliOptions options = CliOptions.parse(Arrays.copyOfRange(args, Math.min(1, args.length), args.length));
        Path pluginsDir = Paths.get(options.getOrDefault("dir", "plugins"));
        Path rulesDir = Paths.get(options.getOrDefault("rules", "rules"));

        switch (sub) {
            case "list" -> {
                try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
                    pluginsDir,
                    rulesDir,
                    true,
                    java.util.Set.of(),
                    java.util.Set.of()
                ))) {
                    if (runtime.loadedPlugins().isEmpty()) {
                        out.println("No plugins loaded.");
                    } else {
                        runtime.loadedPlugins().forEach(plugin -> out.println(
                            plugin.id() + " " + plugin.version() + " (" + plugin.source() + ", "
                                + (plugin.enabled() ? "enabled" : "disabled") + ")"
                        ));
                    }
                    runtime.diagnostics().forEach(diagnostic -> out.println("WARN " + diagnostic.message()));
                    return 0;
                }
            }
            case "reload" -> {
                try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
                    pluginsDir,
                    rulesDir,
                    true,
                    java.util.Set.of(),
                    java.util.Set.of()
                ))) {
                    PluginReloadResult result = runtime.reloadWithResult();
                    out.println("Reload complete.");
                    result.pluginChanges().forEach(change -> out.println("  plugin " + change.type() + " " + change.id()));
                    result.ruleFileChanges().forEach(change -> out.println("  rulefile " + change.type() + " " + change.id()));
                    result.diagnostics().forEach(diagnostic -> out.println("  WARN " + diagnostic.message()));
                    result.conflicts().forEach(conflict ->
                        out.println("  CONFLICT " + String.join(", ", conflict.ruleIds())));
                    result.cyclicConflicts().forEach(conflict ->
                        out.println("  CYCLE " + String.join(", ", conflict.ruleIds())));
                    return 0;
                }
            }
            case "status" -> {
                try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
                    pluginsDir,
                    rulesDir,
                    true,
                    java.util.Set.of(),
                    java.util.Set.of()
                ))) {
                    out.println("Plugins directory: " + pluginsDir.toAbsolutePath());
                    out.println("Rules directory: " + rulesDir.toAbsolutePath());
                    out.println("Plugins loaded: " + runtime.loadedPlugins().size());
                    out.println("Rules loaded: " + runtime.registeredRules().size());
                    out.println("Rule files: " + runtime.loadedRuleFiles().size());
                    out.println("Conflicts: " + runtime.conflicts().size());
                    out.println("Cyclic conflicts: " + runtime.cyclicConflicts().size());
                    runtime.diagnostics().forEach(diagnostic -> out.println("WARN " + diagnostic.message()));
                    return 0;
                }
            }
            case "watch" -> {
                try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
                    pluginsDir,
                    rulesDir,
                    true,
                    java.util.Set.of(),
                    java.util.Set.of()
                ))) {
                    out.println("Watching " + pluginsDir + " and " + rulesDir + " for changes. Press Ctrl+C to stop.");
                    try (PluginDirectoryWatcher watcher = new PluginDirectoryWatcher(
                        runtime,
                        java.time.Duration.ofMillis(500),
                        result -> {
                            out.println("[reload] Reload triggered.");
                            result.pluginChanges().forEach(change ->
                                out.println("  plugin " + change.type() + " " + change.id()));
                            result.ruleFileChanges().forEach(change ->
                                out.println("  rulefile " + change.type() + " " + change.id()));
                            result.diagnostics().stream()
                                .filter(diagnostic -> !diagnostic.message().contains("Activation profile"))
                                .forEach(diagnostic -> out.println("  WARN " + diagnostic.message()));
                        }
                    )) {
                        watcher.start();
                        Thread.currentThread().join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return 0;
                } catch (IOException ex) {
                    out.println("Watch failed: " + ex.getMessage());
                    return 2;
                }
            }
            default -> {
                out.println("Usage: plugins list|reload|watch|status [--dir PATH] [--rules PATH]");
                return 1;
            }
        }
    }

    private int runRules(String[] args) {
        if (args.length == 0) {
            out.println("Usage: rules list|validate|conflicts|profiles|debug|import|export");
            return 1;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                CliOptions options = CliOptions.parse(Arrays.copyOfRange(args, 1, args.length));
                Path rulesDir = Paths.get(options.getOrDefault("dir", "rules"));
                String profile = options.getOrDefault("profile", "");
                try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
                    Paths.get("plugins"),
                    rulesDir,
                    true,
                    java.util.Set.of(),
                    java.util.Set.of(),
                    profile
                ))) {
                    if (runtime.registeredRules().isEmpty()) {
                        out.println("No plugin or rule-file rules loaded.");
                    } else {
                        runtime.registeredRules().forEach(rule -> out.println(rule.type() + " "
                            + rule.id() + " (" + rule.source() + ", "
                            + (rule.enabled() ? "enabled" : "disabled") + ")"));
                    }
                    if (!runtime.macroRegistry().registrations().isEmpty()) {
                        runtime.macroRegistry().registrations()
                            .forEach(macro -> out.println("macro " + macro.id() + " (" + macro.source() + ", "
                                + (macro.enabled() ? "enabled" : "disabled") + ")"));
                    }
                    printExtensions("search-strategy", runtime.searchStrategyRegistry().registrations());
                    printExtensions("heuristic", runtime.heuristicRegistry().registrations());
                    printExtensions("cost-function", runtime.costFunctionRegistry().registrations());
                    printExtensions("renderer", runtime.rendererRegistry().registrations());
                    printExtensions("explanation", runtime.explanationRegistry().registrations());
                    printExtensions("parser-extension", runtime.parserExtensionRegistry().registrations());
                    printExtensions("example", runtime.exampleRegistry().registrations());
                    runtime.diagnostics().forEach(diagnostic -> out.println("WARN " + diagnostic.message()));
                    return 0;
                }
            }
            case "validate" -> {
                if (args.length < 2) {
                    out.println("Usage: rules validate <file.regelsuche>");
                    return 1;
                }
                Path file = Paths.get(args[1]);
                try {
                    new PluginRuntime.RuleFileLoader().load(file, new de.regelsuche.plugin.RuleRegistry(),
                        new de.regelsuche.plugin.MacroRegistry());
                    out.println("Rule file is valid: " + file.toAbsolutePath());
                    return 0;
                } catch (RuleFileParseException ex) {
                    ex.diagnostics().forEach(diagnostic -> out.println(diagnostic.format()));
                    return 2;
                }
            }
            case "conflicts" -> {
                CliOptions options = CliOptions.parse(Arrays.copyOfRange(args, 1, args.length));
                Path rulesDir = Paths.get(options.getOrDefault("dir", "rules"));
                try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
                    Paths.get("plugins"),
                    rulesDir,
                    true,
                    java.util.Set.of(),
                    java.util.Set.of()
                ))) {
                    if (runtime.conflicts().isEmpty() && runtime.cyclicConflicts().isEmpty()) {
                        out.println("No rule conflicts detected.");
                    } else {
                        runtime.conflicts().forEach(conflict -> out.println(
                            "CONFLICT competing rules share source pattern: "
                                + String.join(", ", conflict.ruleIds())));
                        runtime.cyclicConflicts().forEach(cycle -> out.println(
                            "CYCLE inverse rules can loop indefinitely: "
                                + String.join(", ", cycle.ruleIds())));
                    }
                    return 0;
                }
            }
            case "profiles" -> {
                CliOptions options = CliOptions.parse(Arrays.copyOfRange(args, 1, args.length));
                Path rulesDir = Paths.get(options.getOrDefault("dir", "rules"));
                String activeProfile = options.getOrDefault("profile", "");
                try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
                    Paths.get("plugins"),
                    rulesDir,
                    true,
                    java.util.Set.of(),
                    java.util.Set.of(),
                    activeProfile
                ))) {
                    if (runtime.profiles().isEmpty()) {
                        out.println("No activation profiles loaded.");
                    } else {
                        runtime.profiles().forEach(profile -> out.println(
                            "profile " + profile.id()
                                + (profile.id().equals(runtime.activeProfile()) ? " [active]" : "")
                                + " (enable: " + String.join(", ", profile.enableTags())
                                + "; disable: " + String.join(", ", profile.disableTags())
                                + "; whitelist: " + String.join(", ", profile.whitelist())
                                + "; blacklist: " + String.join(", ", profile.blacklist()) + ")"));
                    }
                    runtime.diagnostics().forEach(diagnostic -> out.println("WARN " + diagnostic.message()));
                    return 0;
                }
            }
            case "import" -> {
                if (args.length < 2) {
                    out.println("Usage: rules import <file-or-dir> [--into rules/]");
                    return 1;
                }
                CliOptions options = CliOptions.parse(Arrays.copyOfRange(args, 1, args.length));
                Path source = Paths.get(args[1]);
                Path targetDir = Paths.get(options.getOrDefault("into", "rules"));
                return runRulesImport(source, targetDir);
            }
            case "export" -> {
                CliOptions options = CliOptions.parse(Arrays.copyOfRange(args, 1, args.length));
                Path rulesDir = Paths.get(options.getOrDefault("dir", "rules"));
                Path outputDir = Paths.get(options.getOrDefault("out", "exports"));
                String profile = options.getOrDefault("profile", "");
                return runRulesExport(rulesDir, outputDir, profile);
            }
            case "debug" -> {
                if (args.length < 2) {
                    out.println("Usage: rules debug <expression> [--dir rules/]");
                    return 1;
                }
                CliOptions options = CliOptions.parse(Arrays.copyOfRange(args, 1, args.length));
                String expression = args[1];
                Path rulesDir = Paths.get(options.getOrDefault("dir", "rules"));
                return runRulesDebug(expression, rulesDir);
            }
            default -> {
                out.println("Unknown rules command: " + sub);
                return 1;
            }
        }
    }

    private int runInventory(String[] args) {
        if (args.length == 0) {
            out.println("Usage: inventory list|export|enable|disable|import|tag");
            return 1;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "list" -> {
                List<ReusableRule> rules = inventoryRepository.findAll();
                if (rules.isEmpty()) {
                    out.println("Inventory is empty.");
                } else {
                    rules.forEach(rule -> {
                        String enabled = inventoryRepository.isEnabled(rule.id()) ? "enabled" : "disabled";
                        String tags = inventoryRepository.tagsOf(rule.id()).isEmpty()
                            ? ""
                            : " tags=" + inventoryRepository.tagsOf(rule.id());
                        out.println(rule.id() + ": "
                            + rule.leftPattern() + " -> " + rule.rightPattern()
                            + " (" + rule.proofStatus() + ", usage=" + rule.usageCount()
                            + ", " + enabled + ")" + tags);
                    });
                }
                return 0;
            }
            case "enable" -> {
                if (rest.length == 0) {
                    out.println("Usage: inventory enable <ruleId>");
                    return 1;
                }
                inventoryRepository.setEnabled(rest[0], true);
                out.println("Enabled " + rest[0]);
                return 0;
            }
            case "disable" -> {
                if (rest.length == 0) {
                    out.println("Usage: inventory disable <ruleId>");
                    return 1;
                }
                inventoryRepository.setEnabled(rest[0], false);
                out.println("Disabled " + rest[0]);
                return 0;
            }
            case "tag" -> {
                if (rest.length < 2) {
                    out.println("Usage: inventory tag <ruleId> <tag>");
                    return 1;
                }
                inventoryRepository.addTag(rest[0], rest[1]);
                out.println("Tagged " + rest[0] + " with " + rest[1]);
                return 0;
            }
            case "import" -> {
                if (rest.length == 0) {
                    out.println("Usage: inventory import <file.json>");
                    return 1;
                }
                Path source = Paths.get(rest[0]);
                try {
                    String json = java.nio.file.Files.readString(source);
                    de.regelsuche.export.ExportBundle bundle =
                        new de.regelsuche.export.DefaultTransformationImportService().importJson(json);
                    inventoryRepository.importBundle(bundle);
                    out.println("Imported " + bundle.reusableRules().size()
                        + " reusable rule(s) from " + source.toAbsolutePath());
                    return 0;
                } catch (Exception ex) {
                    out.println("Inventory import failed: " + ex.getMessage());
                    return 2;
                }
            }
            case "export" -> {
                CliOptions options = CliOptions.parse(rest);
                String format = options.getOrDefault("format", "json");
                if (!"json".equalsIgnoreCase(format)) {
                    out.println("Only json format is supported for inventory export");
                    return 1;
                }
                String directory = options.getOrDefault("dir", ExportFileService.DEFAULT_DIRECTORY);
                try {
                    List<Path> paths = exportFileService.writeAll(
                        Paths.get(directory),
                        List.of("inventory"),
                        List.of(),
                        List.of(),
                        inventoryRepository.findAll()
                    );
                    paths.forEach(path -> out.println("Exported inventory to " + path.toAbsolutePath()));
                    return 0;
                } catch (Exception ex) {
                    out.println("Inventory export failed: " + ex.getMessage());
                    return 2;
                }
            }
            default -> {
                out.println("Unknown inventory command: " + sub);
                return 1;
            }
        }
    }

    private int runRulesImport(Path source, Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            if (Files.isDirectory(source)) {
                try (var stream = Files.list(source)) {
                    List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                            return name.endsWith(".regelsuche") || name.endsWith(".rules");
                        })
                        .toList();
                    if (files.isEmpty()) {
                        out.println("No rule files found in: " + source);
                        return 1;
                    }
                    for (Path file : files) {
                        Path target = targetDir.resolve(file.getFileName());
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                        out.println("Imported " + file.getFileName() + " -> " + target);
                    }
                }
            } else if (Files.isRegularFile(source)) {
                Path target = targetDir.resolve(source.getFileName());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                out.println("Imported " + source.getFileName() + " -> " + target);
            } else {
                out.println("Not found: " + source);
                return 1;
            }
            return 0;
        } catch (IOException ex) {
            out.println("Import failed: " + ex.getMessage());
            return 2;
        }
    }

    private int runRulesExport(Path rulesDir, Path outputDir, String profile) {
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            Paths.get("plugins"),
            rulesDir,
            true,
            java.util.Set.of(),
            java.util.Set.of(),
            profile.isBlank() ? null : profile
        ))) {
            Files.createDirectories(outputDir);
            StringBuilder sb = new StringBuilder();
            sb.append("# Exported rules").append(System.lineSeparator());
            if (!profile.isBlank()) {
                sb.append("# profile: ").append(profile).append(System.lineSeparator());
            }
            sb.append(System.lineSeparator());
            int exported = 0;
            for (de.regelsuche.plugin.RuleRegistry.RuleRegistration registration : runtime.ruleRegistry().registrations()) {
                if (!registration.enabled()) {
                    continue;
                }
                if (registration.rule() instanceof de.regelsuche.transform.PatternRewriteRule rule) {
                    appendExportedRule(
                        sb,
                        registration.id(),
                        rule.source(),
                        rule.target(),
                        registration.source(),
                        registration.explanation(),
                        registration.tags(),
                        registration.conditions(),
                        -rule.estimatedCostDelta()
                    );
                    exported++;
                }
            }
            for (de.regelsuche.plugin.TransformationRegistry.TransformationRegistration registration
                : runtime.transformationRegistry().registrations()) {
                if (!registration.enabled()) {
                    continue;
                }
                if (registration.transformation() instanceof de.regelsuche.plugin.PatternBasedTransformation transformation) {
                    appendExportedRule(
                        sb,
                        registration.id(),
                        transformation.source(),
                        transformation.target(),
                        registration.source(),
                        registration.explanation(),
                        registration.tags(),
                        List.of(),
                        -transformation.estimatedCostDelta()
                    );
                    exported++;
                }
            }
            String filename = profile.isBlank()
                ? "exported-rules.regelsuche"
                : "exported-rules-" + profile + ".regelsuche";
            Path outFile = outputDir.resolve(filename);
            Files.writeString(outFile, sb.toString());
            out.println("Exported " + exported + " rules to " + outFile.toAbsolutePath());
            runtime.diagnostics().forEach(diagnostic -> out.println("WARN " + diagnostic.message()));
            return 0;
        } catch (IOException ex) {
            out.println("Export failed: " + ex.getMessage());
            return 2;
        }
    }

    private int runRulesDebug(String expression, Path rulesDir) {
        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            Paths.get("plugins"),
            rulesDir,
            true,
            java.util.Set.of(),
            java.util.Set.of()
        ))) {
            de.regelsuche.plugin.PluginAwareAstRewriteTransformationEngine engine = runtime.createTransformationEngine();
            engine.enableDebugMode();
            engine.transform(expression);
            RuleDebugReport report = engine.lastDebugReport();
            if (report == null) {
                out.println("No debug report generated for: " + expression);
                return 1;
            }
            out.println("Debug report for: " + report.expression());
            out.println("Total rule attempts: " + report.totalAttempts());
            out.println("Successful applications: " + report.successfulApplications());
            out.println("Growth limit rejections: " + report.growthLimitRejections());
            out.println("Candidate limit rejections: " + report.candidateLimitRejections());
            out.println("Disabled by config: " + report.disabledByConfigRejections());
            out.println("Disabled by profile: " + report.disabledByProfileRejections());
            out.println("Condition failed: " + report.conditionFailedRejections());
            out.println("Cycle risk: " + report.cycleRiskRejections());
            if (!report.diagnostics().isEmpty()) {
                out.println();
                out.println("Diagnostics:");
                report.diagnostics().forEach(diagnostic -> out.println("  " + diagnostic));
            }
            out.println();
            out.println("Rule attempts (first 50):");
            report.attempts().stream().limit(50).forEach(attempt -> {
                String context = "RUNTIME".equals(attempt.phase()) && attempt.subtree() != null && !attempt.subtree().isBlank()
                    ? " [" + attempt.subtree() + "]" : "";
                out.printf("  %-40s %-8s %-25s %s%s%n",
                    attempt.ruleId(),
                    attempt.matched() ? "APPLIED" : "SKIP",
                    attempt.reason(),
                    attempt.detail(),
                    context);
            });
            return 0;
        }
    }

    private void appendExportedRule(
        StringBuilder sb,
        String id,
        de.regelsuche.transform.PatternExpr source,
        de.regelsuche.transform.PatternExpr target,
        String sourceInfo,
        String explanation,
        List<String> tags,
        List<de.regelsuche.plugin.RuleFileParser.RuleCondition> conditions,
        int priority
    ) {
        sb.append("rule ").append(id).append(":").append(System.lineSeparator());
        sb.append("  pattern: ").append(formatPatternExpr(source)).append(System.lineSeparator());
        sb.append("  replace: ").append(formatPatternExpr(target)).append(System.lineSeparator());
        sb.append("  direction: forward").append(System.lineSeparator());
        sb.append("  # source: ").append(sourceInfo).append(System.lineSeparator());
        if (!explanation.isBlank()) {
            sb.append("  explanation: \"")
                .append(explanation.replace("\"", "\\\""))
                .append("\"")
                .append(System.lineSeparator());
        }
        if (priority != 0) {
            sb.append("  priority: ").append(priority).append(System.lineSeparator());
        }
        if (!tags.isEmpty()) {
            sb.append("  tags:").append(System.lineSeparator());
            for (String tag : tags) {
                sb.append("    - ").append(tag).append(System.lineSeparator());
            }
        }
        if (!conditions.isEmpty()) {
            sb.append("  conditions:").append(System.lineSeparator());
            for (de.regelsuche.plugin.RuleFileParser.RuleCondition condition : conditions) {
                sb.append("    - ").append(condition.name()).append(": ").append(condition.value())
                    .append(System.lineSeparator());
            }
        }
        sb.append(System.lineSeparator());
    }

    private String formatPatternExpr(de.regelsuche.transform.PatternExpr expr) {
        if (expr instanceof de.regelsuche.transform.PatternExpr.Placeholder placeholder) {
            return placeholder.name();
        }
        if (expr instanceof de.regelsuche.transform.PatternExpr.LiteralNumber number) {
            double value = number.value();
            if (value == Math.rint(value)) {
                return Long.toString((long) value);
            }
            return Double.toString(value);
        }
        if (expr instanceof de.regelsuche.transform.PatternExpr.LiteralVariable variable) {
            return variable.name();
        }
        if (expr instanceof de.regelsuche.transform.PatternExpr.Operation operation) {
            return formatOperation(operation);
        }
        if (expr instanceof de.regelsuche.transform.PatternExpr.Function function) {
            return function.name() + "(" + function.arguments().stream().map(this::formatPatternExpr)
                .collect(java.util.stream.Collectors.joining(", ")) + ")";
        }
        throw new IllegalArgumentException("Unsupported pattern expression: " + expr);
    }

    private String formatOperation(de.regelsuche.transform.PatternExpr.Operation operation) {
        String left = formatPatternOperand(operation.left(), operation.operator().precedence(), false);
        String right = formatPatternOperand(operation.right(), operation.operator().precedence(), true);
        return left + " " + operation.operator().symbol() + " " + right;
    }

    private String formatPatternOperand(de.regelsuche.transform.PatternExpr operand, int parentPrecedence, boolean rightSide) {
        String rendered = formatPatternExpr(operand);
        if (operand instanceof de.regelsuche.transform.PatternExpr.Operation nested) {
            boolean needsParentheses = nested.operator().precedence() < parentPrecedence
                || (rightSide && nested.operator().precedence() == parentPrecedence);
            if (needsParentheses) {
                return "(" + rendered + ")";
            }
        }
        return rendered;
    }

    private void printExtensions(
        String type,
        java.util.List<? extends PluginExtensionRegistry.ExtensionRegistration<?>> registrations
    ) {
        registrations.forEach(registration -> out.println(type + " " + registration.id() + " ("
            + registration.source() + ", " + (registration.enabled() ? "enabled" : "disabled") + ")"));
    }

    private int runBenchmark(String[] args) {
        CliOptions options = CliOptions.parse(args);
        de.regelsuche.benchmark.BenchmarkSuite suite = new de.regelsuche.benchmark.BenchmarkSuite();
        java.util.List<de.regelsuche.benchmark.BenchmarkScenarioResult> results = suite.runAll();
        for (de.regelsuche.benchmark.BenchmarkScenarioResult result : results) {
            out.println("# " + result.name());
            result.results().forEach(row -> out.println("  " + row));
            if (!options.containsKey("quiet")) {
                out.println();
            }
        }
        // Optional report rendering: --report=<md-path> and --summary=<json-path>
        // power the `./gradlew benchmarkReport` workflow that ships
        // docs/benchmark-report.md and docs/assets/benchmark-summary.json.
        if (options.containsKey("report") || options.containsKey("summary")) {
            de.regelsuche.benchmark.BenchmarkReportRenderer renderer =
                new de.regelsuche.benchmark.BenchmarkReportRenderer();
            try {
                if (options.containsKey("report")) {
                    Path reportPath = Paths.get(options.get("report"));
                    Files.createDirectories(reportPath.toAbsolutePath().getParent());
                    Files.writeString(reportPath, renderer.renderMarkdown(results));
                    out.println("Wrote benchmark report: " + reportPath);
                }
                if (options.containsKey("summary")) {
                    Path summaryPath = Paths.get(options.get("summary"));
                    Files.createDirectories(summaryPath.toAbsolutePath().getParent());
                    Files.writeString(summaryPath, renderer.renderJsonSummary(results));
                    out.println("Wrote benchmark summary: " + summaryPath);
                }
            } catch (java.io.IOException ex) {
                out.println("Failed to write benchmark artefact: " + ex.getMessage());
                return 2;
            }
        }
        return 0;
    }

    private int runServe(String[] args) {
        CliOptions options = CliOptions.parse(args);
        int port = Integer.parseInt(options.getOrDefault("port", "8080"));
        String host = options.getOrDefault("host", "127.0.0.1");
        de.regelsuche.web.WebSecurityConfig.Builder configBuilder = de.regelsuche.web.WebSecurityConfig.builder();
        boolean securityEnabled = false;
        if (options.containsKey("user") && options.containsKey("password")) {
            configBuilder.basicAuth(options.get("user"), options.get("password"));
            if (options.containsKey("realm")) {
                configBuilder.realm(options.get("realm"));
            }
            securityEnabled = true;
        }
        if (options.containsKey("keystore")) {
            String storePass = options.getOrDefault("keystore-password", "");
            String type = options.getOrDefault("keystore-type", "PKCS12");
            configBuilder.tls(Paths.get(options.get("keystore")), storePass.toCharArray(), type);
            securityEnabled = true;
        }
        if (options.containsKey("max-request-bytes")) {
            try {
                configBuilder.maxRequestBytes(Integer.parseInt(options.get("max-request-bytes")));
            } catch (NumberFormatException ex) {
                out.println("Invalid --max-request-bytes: " + options.get("max-request-bytes"));
                return 2;
            }
        }
        de.regelsuche.web.WebSecurityConfig securityConfig = configBuilder.build();

        // Resolve persistence: if the environment / JVM properties select a
        // non-default mode, route the web workbench through it so the
        // killer-demo's single Docker image can offer file-backed (or remote
        // Neo4j) persistence without any extra wiring.
        de.regelsuche.persistence.PersistenceConfig persistenceConfig =
            de.regelsuche.persistence.PersistenceConfig.fromEnvironment();
        ExpressionGraphStore activeGraphStore = graphStore;
        RuleInventoryRepository activeInventory = inventoryRepository;
        de.regelsuche.search.memory.SearchMemory activeSearchMemory =
            new de.regelsuche.search.memory.SearchMemory();
        de.regelsuche.persistence.PersistenceContext persistenceContext = null;
        if (persistenceConfig.mode() != de.regelsuche.persistence.GraphPersistenceMode.IN_MEMORY) {
            persistenceContext = de.regelsuche.persistence.PersistenceContext.from(persistenceConfig, out);
            activeGraphStore = persistenceContext.graphStore();
            activeInventory = persistenceContext.inventoryRepository();
            activeSearchMemory = new de.regelsuche.search.memory.SearchMemory(
                persistenceContext.transpositionTable());
        }

        // Resolve the proof workbench. When REGELSUCHE_PROOF_ENABLED is true
        // (the default) the scheduler is constructed with persistent JSON
        // stores so jobs/cache/artifacts survive restarts and are exposed via
        // /api/proof/jobs and the Workbench UI.
        de.regelsuche.proof.ProofConfig proofConfig =
            de.regelsuche.proof.ProofConfig.fromEnvironment(persistenceConfig.storagePath());
        de.regelsuche.proof.ProofWorkbenchService proofWorkbench = null;
        de.regelsuche.proof.ProofJobScheduler proofScheduler = null;
        if (proofConfig.enabled()) {
            try {
                de.regelsuche.proof.JsonFileProofJobRepository jobs =
                    new de.regelsuche.proof.JsonFileProofJobRepository(proofConfig.jobStorePath());
                de.regelsuche.proof.JsonFileProofCache cache =
                    new de.regelsuche.proof.JsonFileProofCache(proofConfig.cachePath());
                de.regelsuche.proof.JsonFileProofArtifactRepository artifacts =
                    new de.regelsuche.proof.JsonFileProofArtifactRepository(proofConfig.artifactPath());
                de.regelsuche.proof.ProofWorker worker = new de.regelsuche.proof.CompositeProofWorker(
                    java.util.List.of(
                        new de.regelsuche.proof.LeanProofWorker(proofConfig.artifactPath()),
                        new de.regelsuche.proof.SmtProofWorker(proofConfig.artifactPath())
                    )
                );
                proofScheduler = new de.regelsuche.proof.ProofJobScheduler(
                    worker, jobs, cache, activeInventory, artifacts,
                    java.time.Duration.ofSeconds(60)
                );
                proofScheduler.start();
                proofWorkbench = new de.regelsuche.proof.ProofWorkbenchService(
                    proofScheduler, jobs, artifacts);
                out.println("Proof workbench enabled: jobs=" + proofConfig.jobStorePath()
                    + ", cache=" + proofConfig.cachePath()
                    + ", artifacts=" + proofConfig.artifactPath());
            } catch (java.io.IOException ex) {
                out.println("Proof workbench initialisation failed (" + ex.getMessage()
                    + "); REST endpoints will report 503.");
            }
        }

        try {
            de.regelsuche.web.WebWorkbenchServer server = new de.regelsuche.web.WebWorkbenchServer(
                host, port, activeGraphStore, activeInventory, exportService, securityConfig, activeSearchMemory,
                null, null, proofWorkbench
            );
            server.start();
            String scheme = securityConfig.isTlsEnabled() ? "https" : "http";
            out.println("Web workbench listening on " + scheme + "://" + host + ":" + port
                + (securityEnabled ? " (secured)" : ""));
            out.println("Press Ctrl+C to stop.");
            // Block forever (until interrupted).
            try {
                Thread.currentThread().join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                server.stop();
            }
            return 0;
        } catch (Exception ex) {
            out.println("serve failed: " + ex.getMessage());
            return 2;
        } finally {
            if (proofScheduler != null) {
                proofScheduler.close();
            }
            if (persistenceContext != null) {
                persistenceContext.close();
            }
        }
    }

    private int runExplain(String[] args) {
        if (args.length < 1) {
            out.println("Usage: explain <pathId> [--form short|school|expert|latex|json]");
            return 1;
        }
        String pathId = args[0];
        CliOptions options = CliOptions.parse(Arrays.copyOfRange(args, 1, args.length));
        String formName = options.getOrDefault("form", "school").toUpperCase(Locale.ROOT);
        de.regelsuche.explain.ExplanationService.Form form;
        try {
            form = de.regelsuche.explain.ExplanationService.Form.valueOf(formName);
        } catch (IllegalArgumentException ex) {
            out.println("Unsupported form: " + options.get("form"));
            return 1;
        }
        Optional<DiscoveredTransformation> match = graphStore.discoveredTransformations().stream()
            .filter(transformation -> transformation.id().equals(pathId))
            .findFirst();
        if (match.isEmpty()) {
            out.println("Path " + pathId + " not found");
            return 1;
        }
        out.println(new de.regelsuche.explain.ExplanationService().renderPath(match.get(), form));
        return 0;
    }

    private int runPath(String[] args) {
        if (args.length < 2 || !"show".equalsIgnoreCase(args[0])) {
            out.println("Usage: path show <pathId> [--format markdown|latex|mermaid|json]");
            return 1;
        }
        String pathId = args[1];
        CliOptions options = CliOptions.parse(Arrays.copyOfRange(args, 2, args.length));
        String format = options.getOrDefault("format", "markdown");
        Optional<DiscoveredTransformation> match = graphStore.discoveredTransformations().stream()
            .filter(transformation -> transformation.id().equals(pathId))
            .findFirst();
        if (match.isEmpty()) {
            out.println("Path " + pathId + " not found");
            return 1;
        }
        List<DiscoveredTransformation> wrapper = List.of(match.get());
        switch (format.toLowerCase(Locale.ROOT)) {
            case "markdown", "md" -> out.println(exportService.exportMarkdown(wrapper));
            case "latex", "tex" -> out.println(exportService.exportLatex(wrapper));
            case "mermaid", "mmd" -> out.println(exportService.exportMermaid(wrapper));
            case "json" -> out.println(exportService.exportJson(wrapper, List.of(), List.of()));
            default -> {
                out.println("Unsupported format: " + format);
                return 1;
            }
        }
        return 0;
    }

    private void close(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
        }
    }
}
