package de.regelsuche.export;

import de.regelsuche.api.IdentityReportDto;
import de.regelsuche.api.searchgraph.SearchGraphDto;
import de.regelsuche.api.searchgraph.SearchGraphEdgeDto;
import de.regelsuche.api.searchgraph.SearchGraphJsonSerializer;
import de.regelsuche.api.searchgraph.SearchGraphNodeDto;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.RuleCandidate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    /** Serialises a {@link SearchGraphDto} to the JSON shape consumed by the UI. */
    default String exportSearchGraphJson(SearchGraphDto searchGraph) {
        return SearchGraphJsonSerializer.toJson(searchGraph);
    }

    /**
     * Renders a {@link SearchGraphDto} as Mermaid {@code graph TD} with CSS
     * classes for best-path / dead-end / cluster nodes. Used as a Cytoscape
     * fallback in the UI.
     */
    default String exportSearchGraphMermaid(SearchGraphDto searchGraph) {
        StringBuilder builder = new StringBuilder("graph TD\n");
        java.util.Map<String, String> nodeIds = new java.util.LinkedHashMap<>();
        int counter = 0;
        for (SearchGraphNodeDto node : searchGraph.nodes()) {
            String safe = "N" + counter++;
            nodeIds.put(node.id(), safe);
            builder.append("  ").append(safe)
                .append("[\"").append(node.expression().replace("\"", "'")).append("\"]\n");
        }
        for (SearchGraphEdgeDto edge : searchGraph.edges()) {
            String from = nodeIds.get(edge.from());
            String to = nodeIds.get(edge.to());
            if (from == null || to == null) {
                // Skip edges that reference nodes not present in the graph
                // rather than silently redirecting them to a placeholder.
                continue;
            }
            builder.append("  ").append(from).append(" -->|")
                .append(edge.ruleId().replace("\"", "'"))
                .append("| ").append(to).append('\n');
        }
        for (SearchGraphNodeDto node : searchGraph.nodes()) {
            String safe = nodeIds.get(node.id());
            if (node.isBest()) {
                builder.append("  class ").append(safe).append(" best;\n");
            } else if (node.isDeadEnd()) {
                builder.append("  class ").append(safe).append(" deadend;\n");
            }
        }
        if (!searchGraph.nodes().isEmpty()) {
            builder.append("  classDef best fill:#cfe2ff,stroke:#1a73e8,stroke-width:2px;\n");
            builder.append("  classDef deadend fill:#fce8e6,stroke:#ea4335;\n");
        }
        return builder.toString();
    }

    /**
     * Tiny GraphML writer (XML string-building, no external dependency) for
     * use in tools like yEd / Gephi.
     */
    default String exportSearchGraphGraphMl(SearchGraphDto searchGraph) {
        StringBuilder builder = new StringBuilder();
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        builder.append("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">\n");
        builder.append("  <key id=\"d_expression\" for=\"node\" attr.name=\"expression\" attr.type=\"string\"/>\n");
        builder.append("  <key id=\"d_score\" for=\"node\" attr.name=\"score\" attr.type=\"int\"/>\n");
        builder.append("  <key id=\"d_best\" for=\"node\" attr.name=\"isBest\" attr.type=\"boolean\"/>\n");
        builder.append("  <key id=\"d_deadend\" for=\"node\" attr.name=\"isDeadEnd\" attr.type=\"boolean\"/>\n");
        builder.append("  <key id=\"d_rule\" for=\"edge\" attr.name=\"ruleId\" attr.type=\"string\"/>\n");
        builder.append("  <graph id=\"G\" edgedefault=\"directed\">\n");
        for (SearchGraphNodeDto node : searchGraph.nodes()) {
            builder.append("    <node id=\"").append(xmlAttr(node.id())).append("\">\n");
            builder.append("      <data key=\"d_expression\">").append(xmlText(node.expression())).append("</data>\n");
            builder.append("      <data key=\"d_score\">").append(node.score()).append("</data>\n");
            builder.append("      <data key=\"d_best\">").append(node.isBest()).append("</data>\n");
            builder.append("      <data key=\"d_deadend\">").append(node.isDeadEnd()).append("</data>\n");
            builder.append("    </node>\n");
        }
        int edgeIndex = 0;
        for (SearchGraphEdgeDto edge : searchGraph.edges()) {
            builder.append("    <edge id=\"e").append(edgeIndex++).append("\" source=\"")
                .append(xmlAttr(edge.from())).append("\" target=\"")
                .append(xmlAttr(edge.to())).append("\">\n");
            builder.append("      <data key=\"d_rule\">").append(xmlText(edge.ruleId())).append("</data>\n");
            builder.append("    </edge>\n");
        }
        builder.append("  </graph>\n</graphml>\n");
        return builder.toString();
    }

    /** Markdown rendering of the single best path for didactic distribution. */
    default String exportBestPathMarkdown(List<DiscoveredTransformation> transformations) {
        Optional<DiscoveredTransformation> best = transformations.stream()
            .max(java.util.Comparator.comparingInt(DiscoveredTransformation::totalImprovement));
        if (best.isEmpty()) {
            return "# Best Path\n\n_Keine erfolgreichen Pfade gefunden._\n";
        }
        return exportMarkdown(List.of(best.get()));
    }

    /**
     * Compact LaTeX report combining search-graph summary, best path,
     * candidates and emergent identities. Designed for paper / handout
     * distribution (Step 5 of the Visual-Search-Graph plan).
     */
    default String exportIdentityReportLatex(
        SearchGraphDto searchGraph,
        List<DiscoveredTransformation> transformations,
        List<RuleCandidate> candidates,
        List<IdentityReportDto> identities
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("\\documentclass{article}\n\\begin{document}\n");
        builder.append("\\section*{Suchgraph-Bericht}\n");
        builder.append("Knoten: ").append(searchGraph.stats().nodesVisited())
            .append(", Kanten: ").append(searchGraph.stats().edgesGenerated())
            .append(", Sackgassen: ").append(searchGraph.stats().deadEnds())
            .append(", Bester Score: ").append(searchGraph.stats().bestScore())
            .append("\n\n");
        builder.append("\\section*{Bester Pfad}\n");
        builder.append(exportLatex(transformations.stream()
            .max(java.util.Comparator.comparingInt(DiscoveredTransformation::totalImprovement))
            .map(List::of).orElse(List.of())));
        builder.append("\n\n\\section*{Regelkandidaten}\n");
        for (RuleCandidate candidate : candidates) {
            builder.append("\\textbf{").append(candidate.leftPattern())
                .append(" $\\to$ ").append(candidate.rightPattern())
                .append("} – Status: ").append(candidate.proofStatus().name()).append("\\\\\n");
        }
        builder.append("\n\\section*{Emergent Identities}\n");
        for (IdentityReportDto identity : identities) {
            builder.append("\\textbf{").append(identity.leftPattern())
                .append(" $\\to$ ").append(identity.rightPattern())
                .append("} – occurrences: ").append(identity.occurrences())
                .append(", bekannt: ").append(identity.knownRuleStatus().name())
                .append("\\\\\n");
        }
        builder.append("\\end{document}\n");
        return builder.toString();
    }

    private static String xmlText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String xmlAttr(String value) {
        return xmlText(value).replace("\"", "&quot;");
    }

    default void writeExports(Path exportDirectory, List<DiscoveredTransformation> transformations, List<ReusableRule> rules)
        throws IOException {
        Files.createDirectories(exportDirectory);
        Files.writeString(exportDirectory.resolve("discovered-transformations.md"), exportMarkdown(transformations));
        Files.writeString(exportDirectory.resolve("rule-inventory.json"), exportJson(transformations, rules));
        Files.writeString(exportDirectory.resolve("transformation-graph.mmd"), exportMermaid(transformations));
    }
}
