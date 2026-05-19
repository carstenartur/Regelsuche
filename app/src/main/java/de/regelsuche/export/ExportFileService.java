package de.regelsuche.export;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.RuleCandidate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Writes export artifacts (JSON, Markdown, LaTeX, Mermaid, inventory) to disk.
 * Returns the absolute paths so the CLI can report where files were stored.
 */
public class ExportFileService {
    public static final String DEFAULT_DIRECTORY = "exports";
    public static final Set<String> SUPPORTED_FORMATS =
        Set.of("json", "markdown", "md", "latex", "tex", "mermaid", "mmd", "inventory");

    private final TransformationExportService exportService;

    public ExportFileService(TransformationExportService exportService) {
        this.exportService = exportService;
    }

    public List<Path> writeAll(
        Path directory,
        List<String> formats,
        List<DiscoveredTransformation> transformations,
        List<RuleCandidate> candidates,
        List<ReusableRule> reusableRules
    ) throws IOException {
        Files.createDirectories(directory);
        Set<String> normalized = normalize(formats);
        List<Path> written = new ArrayList<>();
        if (normalized.contains("json")) {
            Path path = directory.resolve("discovered-transformations.json");
            Files.writeString(path, exportService.exportJson(transformations, candidates, reusableRules));
            written.add(path);
        }
        if (normalized.contains("md")) {
            Path path = directory.resolve("discovered-transformations.md");
            Files.writeString(path, exportService.exportMarkdown(transformations));
            written.add(path);
        }
        if (normalized.contains("tex")) {
            Path path = directory.resolve("discovered-transformations.tex");
            Files.writeString(path, exportService.exportLatex(transformations));
            written.add(path);
        }
        if (normalized.contains("mmd")) {
            Path path = directory.resolve("transformation-graph.mmd");
            Files.writeString(path, exportService.exportMermaid(transformations));
            written.add(path);
        }
        if (normalized.contains("inventory")) {
            Path path = directory.resolve("rule-inventory.json");
            Files.writeString(path, exportService.exportJson(List.of(), List.of(), reusableRules));
            written.add(path);
        }
        return written;
    }

    private Set<String> normalize(List<String> formats) {
        Set<String> result = new LinkedHashSet<>();
        for (String format : formats) {
            switch (format.toLowerCase(Locale.ROOT)) {
                case "json" -> result.add("json");
                case "markdown", "md" -> result.add("md");
                case "latex", "tex" -> result.add("tex");
                case "mermaid", "mmd" -> result.add("mmd");
                case "inventory" -> result.add("inventory");
                default -> throw new IllegalArgumentException("Unsupported export format: " + format);
            }
        }
        return result;
    }
}
