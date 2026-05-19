package de.regelsuche.cli;

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
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.TransformationSearchService;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.SymPyTransformationEngine;
import de.regelsuche.transform.TransformationEngine;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dispatches CLI subcommands ({@code discover}, {@code transform},
 * {@code inventory list/export}, {@code path show}) used by the
 * {@link de.regelsuche.App} entry point.
 */
public class CliRouter {
    private static final Set<String> SUBCOMMANDS = Set.of("discover", "transform", "inventory", "path");

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
        return SUBCOMMANDS.contains(token.toLowerCase(Locale.ROOT));
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
        Map<String, String> options = parseOptions(args);
        int min = Integer.parseInt(options.getOrDefault("min", "1"));
        int max = Integer.parseInt(options.getOrDefault("max", "3"));
        List<String> formats = splitCsv(options.getOrDefault("export", ""));
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
        TransformationEngine engine = new SymPyTransformationEngine();
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

    private int runInventory(String[] args) {
        if (args.length == 0) {
            out.println("Usage: inventory list|export");
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
                    rules.forEach(rule -> out.println(rule.id() + ": "
                        + rule.leftPattern() + " -> " + rule.rightPattern()
                        + " (" + rule.proofStatus() + ", usage=" + rule.usageCount() + ")"));
                }
                return 0;
            }
            case "export" -> {
                Map<String, String> options = parseOptions(rest);
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

    private int runPath(String[] args) {
        if (args.length < 2 || !"show".equalsIgnoreCase(args[0])) {
            out.println("Usage: path show <pathId> [--format markdown|latex|mermaid|json]");
            return 1;
        }
        String pathId = args[1];
        Map<String, String> options = parseOptions(Arrays.copyOfRange(args, 2, args.length));
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

    private Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new java.util.LinkedHashMap<>();
        int index = 0;
        while (index < args.length) {
            String current = args[index];
            if (current.startsWith("--")) {
                String key = current.substring(2);
                String value;
                if (index + 1 < args.length && !args[index + 1].startsWith("--")) {
                    value = args[index + 1];
                    index += 2;
                } else {
                    value = "true";
                    index++;
                }
                options.put(key, value);
            } else {
                index++;
            }
        }
        return options;
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private void close(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
        }
    }
}
