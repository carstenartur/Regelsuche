package de.regelsuche.search.convergence;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Renders a convergent discovery SVG from report data only. */
public final class ConvergentDiscoverySvgWriter {
    private static final String SOURCE = "convergent-sophie-germain.mmd";
    private static final String GENERATED_BY = "ConvergentDiscoverySvgWriter";
    private static final int MARGIN_X = 60;
    private static final int MARGIN_Y = 120;
    private static final int COLUMN_WIDTH = 330;
    private static final int ROW_HEIGHT = 150;
    private static final int NODE_WIDTH = 250;
    private static final int NODE_HEIGHT = 82;

    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    public String render(ConvergentDiscoveryReport report) {
        GraphLayout layout = layout(report);
        StringBuilder out = new StringBuilder();
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
            .append(layout.width())
            .append("\" height=\"")
            .append(layout.height())
            .append("\" viewBox=\"0 0 ")
            .append(layout.width())
            .append(' ')
            .append(layout.height())
            .append("\" role=\"img\" aria-labelledby=\"title desc\" data-source=\"")
            .append(SOURCE)
            .append("\" data-generated-by=\"")
            .append(GENERATED_BY)
            .append("\">\n");
        out.append("  <title id=\"title\">Convergent Sophie-Germain discovery graph</title>\n");
        out.append("  <desc id=\"desc\">Generated from convergent discovery report data.</desc>\n");
        out.append("  <metadata>");
        out.append(escapeXml(report.inputExpression()));
        for (ConvergentPath path : report.pathsToTarget()) {
            for (String expression : path.expressions()) {
                out.append(" | ").append(escapeXml(expression));
            }
            for (int i = 0; i < path.ruleIds().size(); i++) {
                out.append(" | ").append(escapeXml(path.ruleFamilies().get(i) + ": " + path.ruleIds().get(i)));
            }
        }
        out.append("</metadata>\n");
        out.append("  <defs>\n");
        out.append("    <marker id=\"arrow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" ")
            .append("markerWidth=\"9\" markerHeight=\"9\" orient=\"auto-start-reverse\">\n");
        out.append("      <path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"#334155\"/>\n");
        out.append("    </marker>\n");
        out.append("  </defs>\n");
        out.append("  <rect width=\"100%\" height=\"100%\" fill=\"#f8fafc\"/>\n");
        out.append("  <text x=\"").append(layout.width() / 2).append("\" y=\"52\" text-anchor=\"middle\" ")
            .append("font-family=\"Inter,Segoe UI,Arial,sans-serif\" font-size=\"26\" ")
            .append("font-weight=\"700\" fill=\"#0f172a\">")
            .append(escapeXml("Convergent discovery: " + report.inputExpression()))
            .append("</text>\n");
        out.append("  <text x=\"").append(layout.width() / 2).append("\" y=\"84\" text-anchor=\"middle\" ")
            .append("font-family=\"Inter,Segoe UI,Arial,sans-serif\" font-size=\"15\" fill=\"#475569\">")
            .append(escapeXml("Generated from report data; " + report.pathsToTarget().size()
                + " paths converge."))
            .append("</text>\n");
        Set<String> renderedEdges = new HashSet<>();
        for (ConvergentPath path : report.pathsToTarget()) {
            for (int i = 1; i < path.expressions().size(); i++) {
                Node from = layout.nodes().get(key(path.expressions().get(i - 1)));
                Node to = i == path.expressions().size() - 1
                    ? layout.convergenceNode()
                    : layout.nodes().get(key(path.expressions().get(i)));
                if (from == null || to == null) {
                    continue;
                }
                String rule = path.ruleFamilies().get(i - 1) + ": " + path.ruleIds().get(i - 1);
                String edgeKey = from.id() + "->" + to.id() + "|" + rule;
                if (!from.id().equals(to.id()) && renderedEdges.add(edgeKey)) {
                    edge(out, from, to, rule, path.containsMacroStep());
                }
            }
        }
        for (Node node : layout.nodes().values()) {
            node(out, node);
        }
        out.append("</svg>\n");
        return out.toString();
    }

    private GraphLayout layout(ConvergentDiscoveryReport report) {
        int pathCount = Math.max(1, report.pathsToTarget().size());
        int maxDepth = report.pathsToTarget().stream()
            .mapToInt(path -> Math.max(1, path.expressions().size() - 1))
            .max()
            .orElse(1);
        int width = MARGIN_X * 2 + COLUMN_WIDTH * maxDepth + NODE_WIDTH;
        int height = MARGIN_Y * 2 + ROW_HEIGHT * pathCount;
        Map<String, Node> nodes = new LinkedHashMap<>();
        Node input = new Node(key(report.inputExpression()), report.inputExpression(), "input",
            MARGIN_X, MARGIN_Y + (pathCount - 1) * ROW_HEIGHT / 2);
        nodes.put(input.id(), input);
        String target = report.convergentStates().isEmpty()
            ? report.canonicalTargetExpression()
            : report.convergentStates().getFirst().expression();
        Node convergence = new Node(key(target), target, "convergence",
            MARGIN_X + COLUMN_WIDTH * maxDepth, input.y());
        nodes.put(convergence.id(), convergence);
        String didacticPathId = report.convergentStates().isEmpty()
            ? null
            : report.convergentStates().getFirst().mostDidacticPathId();
        for (int row = 0; row < report.pathsToTarget().size(); row++) {
            ConvergentPath path = report.pathsToTarget().get(row);
            for (int depth = 1; depth < path.expressions().size() - 1; depth++) {
                String expression = path.expressions().get(depth);
                nodes.putIfAbsent(key(expression), new Node(
                    key(expression),
                    expression,
                    didacticPathId != null && path.pathId().equals(didacticPathId)
                        ? "didactic" : "path",
                    MARGIN_X + COLUMN_WIDTH * depth,
                    MARGIN_Y + row * ROW_HEIGHT
                ));
            }
        }
        return new GraphLayout(width, height, nodes, convergence);
    }

    private void edge(StringBuilder out, Node from, Node to, String label, boolean macro) {
        int startX = from.x() + NODE_WIDTH;
        int startY = from.y() + NODE_HEIGHT / 2;
        int endX = to.x();
        int endY = to.y() + NODE_HEIGHT / 2;
        int midX = (startX + endX) / 2;
        int midY = (startY + endY) / 2 - 10;
        out.append("  <path d=\"M ").append(startX).append(' ').append(startY)
            .append(" C ").append(midX).append(' ').append(startY)
            .append(", ").append(midX).append(' ').append(endY)
            .append(", ").append(endX).append(' ').append(endY)
            .append("\" fill=\"none\" stroke=\"")
            .append(macro ? "#0284c7" : "#334155")
            .append("\" stroke-width=\"3\" marker-end=\"url(#arrow)\"/>\n");
        out.append("  <text x=\"").append(midX).append("\" y=\"").append(midY)
            .append("\" text-anchor=\"middle\" font-family=\"Inter,Segoe UI,Arial,sans-serif\" ")
            .append("font-size=\"13\" font-weight=\"700\" fill=\"")
            .append(macro ? "#075985" : "#334155")
            .append("\">")
            .append(escapeXml(label))
            .append("</text>\n");
    }

    private void node(StringBuilder out, Node node) {
        Style style = Style.of(node.kind());
        out.append("  <g data-node-id=\"").append(escapeXml(node.id())).append("\">\n");
        out.append("    <rect x=\"").append(node.x()).append("\" y=\"").append(node.y())
            .append("\" width=\"").append(NODE_WIDTH).append("\" height=\"").append(NODE_HEIGHT)
            .append("\" rx=\"16\" fill=\"").append(style.fill()).append("\" stroke=\"")
            .append(style.stroke()).append("\" stroke-width=\"3\"/>\n");
        out.append("    <text x=\"").append(node.x() + NODE_WIDTH / 2).append("\" y=\"")
            .append(node.y() + 31)
            .append("\" text-anchor=\"middle\" font-family=\"Inter,Segoe UI,Arial,sans-serif\" ")
            .append("font-size=\"14\" font-weight=\"700\" fill=\"").append(style.labelFill())
            .append("\">").append(escapeXml(node.kind())).append("</text>\n");
        out.append("    <text x=\"").append(node.x() + NODE_WIDTH / 2).append("\" y=\"")
            .append(node.y() + 58)
            .append("\" text-anchor=\"middle\" font-family=\"ui-monospace,SFMono-Regular,")
            .append("Menlo,Consolas,monospace\" font-size=\"14\" fill=\"#111827\">")
            .append(escapeXml(node.label()))
            .append("</text>\n");
        out.append("  </g>\n");
    }

    private String key(String expression) {
        return "conv_" + canonicalizer.stableHash(expression == null ? "" : expression);
    }

    private String escapeXml(String value) {
        return (value == null ? "" : value)
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private record GraphLayout(int width, int height, Map<String, Node> nodes, Node convergenceNode) {
    }

    private record Node(String id, String label, String kind, int x, int y) {
    }

    private record Style(String fill, String stroke, String labelFill) {
        private static Style of(String kind) {
            return switch (kind) {
                case "input" -> new Style("#eef2ff", "#4338ca", "#312e81");
                case "convergence" -> new Style("#dcfce7", "#15803d", "#14532d");
                case "didactic" -> new Style("#fef3c7", "#d97706", "#92400e");
                default -> new Style("#e0f2fe", "#0284c7", "#075985");
            };
        }
    }
}
