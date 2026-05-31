package de.regelsuche.search.convergence;

import de.regelsuche.search.convergence.SearchSpaceSubgraph.Edge;
import de.regelsuche.search.convergence.SearchSpaceSubgraph.Node;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Renders a generated bounded search-space subgraph as SVG. */
public final class SearchSpaceSvgWriter {
    private static final String GENERATED_BY = "SearchSpaceSvgWriter";
    private static final int MARGIN_X = 56;
    private static final int MARGIN_Y = 140;
    private static final int COLUMN_WIDTH = 310;
    private static final int ROW_HEIGHT = 118;
    private static final int NODE_WIDTH = 240;
    private static final int NODE_HEIGHT = 78;

    public String render(SearchSpaceSubgraph graph, ArtifactMetadata metadata) {
        return render(graph, metadata, SearchSpaceSvgOptions.detailed());
    }

    public String render(SearchSpaceSubgraph graph, ArtifactMetadata metadata, SearchSpaceSvgOptions options) {
        ArtifactMetadata safeMetadata = metadata == null ? new ArtifactMetadata("", "") : metadata;
        SearchSpaceSvgOptions safeOptions = options == null ? SearchSpaceSvgOptions.detailed() : options;
        Map<String, PositionedNode> layout = layout(graph);
        int maxDepth = graph.nodes().stream().mapToInt(Node::depth).max().orElse(0);
        int maxRows = graph.nodes().stream().collect(Collectors.groupingBy(Node::depth, Collectors.counting()))
            .values().stream().mapToInt(Long::intValue).max().orElse(1);
        int width = MARGIN_X * 2 + COLUMN_WIDTH * (maxDepth + 1) + NODE_WIDTH;
        int height = MARGIN_Y * 2 + ROW_HEIGHT * Math.max(1, maxRows);
        StringBuilder out = new StringBuilder();
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
            .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ")
            .append(width).append(' ').append(height)
            .append("\" role=\"img\" aria-labelledby=\"title desc\" data-source=\"")
            .append(escapeXml(safeMetadata.dataSource()))
            .append("\" data-generated-by=\"").append(GENERATED_BY)
            .append("\" data-generated-from=\"SearchSpaceSubgraph\">\n");
        out.append("  <title id=\"title\">").append(escapeXml(title(graph, safeMetadata))).append("</title>\n");
        out.append("  <desc id=\"desc\">Bounded replay of an explored search space (reconstructed subgraph): "
            + "explored alternatives, selected paths and convergence.</desc>\n");
        out.append("  <defs>\n");
        out.append("    <marker id=\"spaceArrow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"8\" markerHeight=\"8\" orient=\"auto-start-reverse\">\n");
        out.append("      <path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"#334155\"/>\n");
        out.append("    </marker>\n");
        out.append("  </defs>\n");
        out.append("  <rect width=\"100%\" height=\"100%\" fill=\"#f8fafc\"/>\n");
        out.append("  <text x=\"").append(width / 2).append("\" y=\"48\" text-anchor=\"middle\" ")
            .append("font-family=\"Inter,Segoe UI,Arial,sans-serif\" font-size=\"25\" font-weight=\"700\" fill=\"#0f172a\">")
            .append(escapeXml("Reconstructed search space: " + graph.inputExpression())).append("</text>\n");
        out.append("  <text x=\"").append(width / 2).append("\" y=\"78\" text-anchor=\"middle\" ")
            .append("font-family=\"Inter,Segoe UI,Arial,sans-serif\" font-size=\"14\" fill=\"#475569\">")
            .append(escapeXml(subtitle(graph))).append("</text>\n");
        for (int depth = 0; depth <= maxDepth; depth++) {
            out.append("  <text x=\"").append(MARGIN_X + COLUMN_WIDTH * depth + NODE_WIDTH / 2)
                .append("\" y=\"112\" text-anchor=\"middle\" font-family=\"Inter,Segoe UI,Arial,sans-serif\" ")
                .append("font-size=\"13\" font-weight=\"700\" fill=\"#64748b\">depth ")
                .append(depth).append("</text>\n");
        }
        for (Edge edge : graph.edges()) {
            PositionedNode from = layout.get(edge.fromId());
            PositionedNode to = layout.get(edge.toId());
            if (from != null && to != null && !from.node().id().equals(to.node().id())) {
                edge(out, from, to, edge, safeOptions);
            }
        }
        for (PositionedNode node : layout.values()) {
            node(out, node, safeOptions);
        }
        out.append("</svg>\n");
        return out.toString();
    }

    private String subtitle(SearchSpaceSubgraph graph) {
        return graph.nodes().size() + " states ("
            + graph.originallyExploredCount() + " explored, "
            + graph.reconstructedCount() + " reconstructed), "
            + graph.edges().size() + " rule edges; limits maxStates="
            + graph.maxStates() + ", maxDepth=" + graph.maxDepth() + ".";
    }

    public String render(SearchSpaceSubgraph graph) {
        return render(graph, new ArtifactMetadata("", ""));
    }

    private Map<String, PositionedNode> layout(SearchSpaceSubgraph graph) {
        Map<Integer, List<Node>> byDepth = graph.nodes().stream()
            .sorted(Comparator.comparingInt(Node::depth)
                .thenComparing((Node node) -> node.isTarget() ? 0 : 1)
                .thenComparing(Node::score)
                .thenComparing(Node::expression))
            .collect(Collectors.groupingBy(Node::depth, LinkedHashMap::new, Collectors.toList()));
        Map<String, PositionedNode> positioned = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Node>> entry : byDepth.entrySet()) {
            int depth = entry.getKey();
            List<Node> nodes = entry.getValue();
            for (int row = 0; row < nodes.size(); row++) {
                Node node = nodes.get(row);
                positioned.put(node.id(), new PositionedNode(node,
                    MARGIN_X + depth * COLUMN_WIDTH,
                    MARGIN_Y + row * ROW_HEIGHT));
            }
        }
        return positioned;
    }

    private void edge(StringBuilder out, PositionedNode from, PositionedNode to, Edge edge,
            SearchSpaceSvgOptions options) {
        int startX = from.x() + NODE_WIDTH;
        int startY = from.y() + NODE_HEIGHT / 2;
        int endX = to.x();
        int endY = to.y() + NODE_HEIGHT / 2;
        int midX = (startX + endX) / 2;
        int midY = (startY + endY) / 2 - 8;
        EdgeStyle style = EdgeStyle.of(edge);
        out.append("  <path data-edge-id=\"").append(escapeXml(edge.id()))
            .append("\" data-rule-family=\"").append(edge.ruleFamily())
            .append("\" data-not-selected=\"").append(edge.notSelected())
            .append("\" d=\"M ").append(startX).append(' ').append(startY)
            .append(" C ").append(midX).append(' ').append(startY)
            .append(", ").append(midX).append(' ').append(endY)
            .append(", ").append(endX).append(' ').append(endY)
            .append("\" fill=\"none\" stroke=\"").append(style.stroke())
            .append("\" stroke-width=\"").append(style.width())
            .append("\" opacity=\"").append(style.opacity())
            .append("\" marker-end=\"url(#spaceArrow)\"/>\n");
        if (options.showEdgeLabels()) {
            out.append("  <text x=\"").append(midX).append("\" y=\"").append(midY)
                .append("\" text-anchor=\"middle\" font-family=\"Inter,Segoe UI,Arial,sans-serif\" font-size=\"11\" ")
                .append("font-weight=\"700\" fill=\"").append(style.labelFill()).append("\" opacity=\"")
                .append(style.opacity()).append("\">").append(escapeXml(shortRule(edge, options))).append("</text>\n");
        }
    }

    private void node(StringBuilder out, PositionedNode positioned, SearchSpaceSvgOptions options) {
        Node node = positioned.node();
        NodeStyle style = NodeStyle.of(node);
        out.append("  <g data-node-id=\"").append(escapeXml(node.id()))
            .append("\" data-role=\"").append(node.role())
            .append("\" data-canonical-key=\"").append(escapeXml(node.canonicalKey()))
            .append("\" data-convergence=\"").append(node.isConvergencePoint())
            .append("\" data-originally-explored=\"").append(node.originallyExplored())
            .append("\" data-depth=\"").append(node.depth())
            .append("\" data-score=\"").append(node.score())
            .append("\" data-target=\"").append(node.isTarget())
            .append("\" data-didactic=\"").append(node.isOnDidacticPath())
            .append("\" data-macro=\"").append(node.isOnMacroPath())
            .append("\" data-dead-end=\"").append(node.isDeadEnd())
            .append("\" data-not-selected=\"").append(node.notSelected()).append("\">\n");
        out.append("    <rect x=\"").append(positioned.x()).append("\" y=\"").append(positioned.y())
            .append("\" width=\"").append(NODE_WIDTH).append("\" height=\"").append(NODE_HEIGHT)
            .append("\" rx=\"14\" fill=\"").append(style.fill()).append("\" stroke=\"")
            .append(style.stroke()).append("\" stroke-width=\"").append(style.strokeWidth())
            .append("\" opacity=\"").append(style.opacity()).append("\"/>\n");
        if (options.showLabels()) {
            out.append("    <text x=\"").append(positioned.x() + NODE_WIDTH / 2).append("\" y=\"")
                .append(positioned.y() + 25)
                .append("\" text-anchor=\"middle\" font-family=\"Inter,Segoe UI,Arial,sans-serif\" ")
                .append("font-size=\"12\" font-weight=\"700\" fill=\"").append(style.labelFill())
                .append("\">").append(escapeXml(nodeLabel(node))).append("</text>\n");
        }
        out.append("    <text x=\"").append(positioned.x() + NODE_WIDTH / 2).append("\" y=\"")
            .append(positioned.y() + 51)
            .append("\" text-anchor=\"middle\" font-family=\"ui-monospace,SFMono-Regular,Menlo,Consolas,monospace\" ")
            .append("font-size=\"12\" fill=\"#111827\">").append(escapeXml(shortExpression(node.expression())))
            .append("</text>\n");
        if (options.showNodeIds()) {
            out.append("    <text x=\"").append(positioned.x() + NODE_WIDTH / 2).append("\" y=\"")
                .append(positioned.y() + 68)
                .append("\" text-anchor=\"middle\" font-family=\"ui-monospace,SFMono-Regular,Menlo,Consolas,monospace\" ")
                .append("font-size=\"10\" fill=\"#94a3b8\">").append(escapeXml(shortId(node.id())))
                .append("</text>\n");
        } else {
            out.append("    <text x=\"").append(positioned.x() + NODE_WIDTH / 2).append("\" y=\"")
                .append(positioned.y() + 68)
                .append("\" text-anchor=\"middle\" font-family=\"Inter,Segoe UI,Arial,sans-serif\" ")
                .append("font-size=\"10\" fill=\"#64748b\">score ").append(node.score()).append("</text>\n");
        }
        out.append("  </g>\n");
    }

    private String shortId(String id) {
        String suffix = id.startsWith("space_") ? id.substring("space_".length()) : id;
        return "#" + suffix.substring(0, Math.min(8, suffix.length()));
    }

    private String nodeLabel(Node node) {
        if (node.role() == SearchSpaceSubgraph.StateRole.ROOT) {
            return "search root";
        }
        if (node.role() == SearchSpaceSubgraph.StateRole.CONVERGENCE_TARGET) {
            return "convergence target";
        }
        if (node.role() == SearchSpaceSubgraph.StateRole.CANONICAL_REPRESENTATIVE) {
            return "canonical representative";
        }
        if (node.isOnDidacticPath() && node.isOnMacroPath()) {
            return "shared selected state";
        }
        if (node.isOnDidacticPath()) {
            return "selected path state";
        }
        if (node.isOnMacroPath()) {
            return "macro-shortcut state";
        }
        if (node.isDeadEnd()) {
            return "dead-end alternative";
        }
        if (node.role() == SearchSpaceSubgraph.StateRole.EQUIVALENT_STATE) {
            return "equivalence-class member";
        }
        return "alternative branch";
    }

    private String shortExpression(String expression) {
        return shorten(expression, 34);
    }

    private String shortRule(Edge edge, SearchSpaceSvgOptions options) {
        if (options.showRuleNames()) {
            return edge.ruleFamily() + ": " + shorten(edge.ruleId(), 26);
        }
        return edge.ruleFamily().toString();
    }

    private String shorten(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }

    private String title(SearchSpaceSubgraph graph, ArtifactMetadata metadata) {
        if (!metadata.title().isBlank()) {
            return metadata.title();
        }
        return "Generated search-space subgraph: " + graph.inputExpression();
    }

    private String escapeXml(String value) {
        return (value == null ? "" : value)
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private record PositionedNode(Node node, int x, int y) {
    }

    private record NodeStyle(String fill, String stroke, String labelFill, int strokeWidth, double opacity) {
        private static NodeStyle of(Node node) {
            if (node.isTarget()) {
                return new NodeStyle("#dcfce7", "#15803d", "#14532d", 4, 1.0);
            }
            if (node.depth() == 0) {
                return new NodeStyle("#eef2ff", "#4338ca", "#312e81", 3, 1.0);
            }
            if (node.isOnDidacticPath()) {
                return new NodeStyle("#fef3c7", "#d97706", "#92400e", 3, 1.0);
            }
            if (node.isOnMacroPath()) {
                return new NodeStyle("#e0f2fe", "#0284c7", "#075985", 3, 1.0);
            }
            if (node.isDeadEnd()) {
                return new NodeStyle("#f1f5f9", "#94a3b8", "#475569", 1, 0.58);
            }
            return new NodeStyle("#f8fafc", "#cbd5e1", "#475569", 1, 0.68);
        }
    }

    private record EdgeStyle(String stroke, String labelFill, int width, double opacity) {
        private static EdgeStyle of(Edge edge) {
            if (edge.isOnDidacticPath()) {
                return new EdgeStyle("#d97706", "#92400e", 4, 1.0);
            }
            if (edge.isOnMacroPath()) {
                return new EdgeStyle("#0284c7", "#075985", 4, 1.0);
            }
            return new EdgeStyle("#94a3b8", "#475569", 2, 0.55);
        }
    }
}
