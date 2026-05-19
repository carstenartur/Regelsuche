package de.regelsuche.export;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.RuleCandidate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public interface TransformationExportService {
    String exportMarkdown(List<DiscoveredTransformation> transformations);

    String exportLatex(List<DiscoveredTransformation> transformations);

    String exportJson(List<DiscoveredTransformation> transformations, List<ReusableRule> rules);

    default String exportJson(
        List<DiscoveredTransformation> transformations,
        List<RuleCandidate> candidates,
        List<ReusableRule> rules
    ) {
        return exportJson(transformations, rules);
    }

    default String exportBundle(ExportBundle bundle) {
        return exportJson(bundle.transformations(), bundle.ruleCandidates(), bundle.reusableRules());
    }

    String exportMermaid(List<DiscoveredTransformation> transformations);

    default void writeExports(Path exportDirectory, List<DiscoveredTransformation> transformations, List<ReusableRule> rules)
        throws IOException {
        Files.createDirectories(exportDirectory);
        Files.writeString(exportDirectory.resolve("discovered-transformations.md"), exportMarkdown(transformations));
        Files.writeString(exportDirectory.resolve("rule-inventory.json"), exportJson(transformations, rules));
        Files.writeString(exportDirectory.resolve("transformation-graph.mmd"), exportMermaid(transformations));
    }
}
