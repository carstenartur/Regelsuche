package de.regelsuche.docs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SearchSpaceGallerySvgWriter {
    private static final int WIDTH = 980;
    private static final int LEFT = 80;
    private static final int RIGHT = 900;
    private static final int TOP = 110;
    private static final int LANE_GAP = 92;

    public String write(DiscoveryBenchmarkEvidence evidence, String evidenceFileName) {
        List<DiscoveryBenchmarkEvidence.EvidenceNode> graphNodes = evidence.nodes();
        Map<String, Point> positions = layout(evidence, graphNodes);
        StringBuilder edges = new StringBuilder();
        for (DiscoveryBenchmarkEvidence.EvidenceEdge edge : evidence.edges()) {
            Point from = positions.get(edge.from());
            Point to = positions.get(edge.to());
            if (from == null || to == null) {
                continue;
            }
            String stroke = strokeFor(edge.kind());
            String cssClass = "edge " + escapeXml(edge.kind());
            int yOffset = "macro".equals(edge.kind()) ? -34 : 0;
            edges.append("<path class=\"").append(cssClass).append("\" d=\"M ").append(from.x() + 42).append(' ').append(from.y() + yOffset)
                    .append(" C ").append((from.x() + to.x()) / 2).append(' ').append(from.y() + yOffset - 28)
                    .append(", ").append((from.x() + to.x()) / 2).append(' ').append(to.y() + yOffset - 28)
                    .append(", ").append(to.x() - 42).append(' ').append(to.y() + yOffset)
                    .append("\" fill=\"none\" stroke=\"").append(stroke).append("\" stroke-width=\"")
                    .append("macro".equals(edge.kind()) || "bridge".equals(edge.kind()) ? 3 : 1.5)
                    .append("\" marker-end=\"url(#arrow)\"");
            if ("macro".equals(edge.kind())) {
                edges.append(" stroke-dasharray=\"8 5\"");
            }
            edges.append(">");
            edges.append("<title>").append(escapeXml(edge.ruleId())).append(" · ").append(escapeXml(edge.source()))
                    .append(" · ").append(escapeXml(edge.packId())).append("</title></path>\n");
            edges.append("<text x=\"").append((from.x() + to.x()) / 2 - 48).append("\" y=\"")
                    .append((from.y() + to.y()) / 2 + yOffset - 10)
                    .append("\" font-size=\"10\" fill=\"").append(stroke).append("\">")
                    .append(escapeXml(shorten(edge.ruleId(), 28))).append("<title>")
                    .append(escapeXml(edge.ruleId())).append("</title></text>\n");
        }

        StringBuilder nodes = new StringBuilder();
        for (DiscoveryBenchmarkEvidence.EvidenceNode node : graphNodes) {
            Point point = positions.get(node.id());
            if (point == null) {
                continue;
            }
            boolean isInput = node.label().equals(evidence.inputExpression());
            boolean isTarget = "target".equals(node.kind()) || node.label().equals(evidence.targetExpression());
            String fill = isTarget ? "#dcfce7" : isInput ? "#dbeafe" : "#f8fafc";
            String stroke = isTarget ? "#16a34a" : isInput ? "#2563eb" : "#334155";
            nodes.append("<g class=\"node ").append(isInput ? "input" : isTarget ? "target" : "state").append("\">");
            nodes.append("<rect id=\"").append(escapeXml(node.id())).append("\" x=\"").append(point.x() - 58)
                    .append("\" y=\"").append(point.y() - 24)
                    .append("\" width=\"116\" height=\"48\" rx=\"12\" fill=\"").append(fill)
                    .append("\" stroke=\"").append(stroke).append("\" stroke-width=\"2\"/>");
            nodes.append("<title>").append(escapeXml(node.label())).append("</title>");
            nodes.append("<text x=\"").append(point.x() - 50).append("\" y=\"").append(point.y() - 3)
                    .append("\" font-size=\"10\">").append(escapeXml(shorten(node.label(), 22))).append("</text>");
            nodes.append("<text x=\"").append(point.x() - 50).append("\" y=\"").append(point.y() + 13)
                    .append("\" font-size=\"9\" fill=\"#64748b\">").append(escapeXml(roleLabel(evidence, node))).append("</text>");
            nodes.append("</g>\n");
        }

        int maxY = positions.values().stream().mapToInt(Point::y).max().orElse(TOP);
        int height = Math.max(300, maxY + 150);
        String smallGraphMessage = evidence.smallGraphMessage() == null || evidence.smallGraphMessage().isBlank()
                ? ""
                : "<text x=\"40\" y=\"" + (height - 22) + "\" font-size=\"12\">" + escapeXml(evidence.smallGraphMessage()) + "</text>";
        return """
                <svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${width}\" height=\"${height}\" viewBox=\"0 0 ${width} ${height}\" data-generated-by=\"SearchSpaceGallerySvgWriter\" data-scenario-id=\"${scenarioId}\" data-evidence=\"${evidenceFile}\" data-node-count=\"${nodeCount}\" data-edge-count=\"${edgeCount}\">
                  <rect width=\"100%\" height=\"100%\" fill=\"#f8fafc\"/>
                  <defs><marker id=\"arrow\" markerWidth=\"10\" markerHeight=\"10\" refX=\"10\" refY=\"3\" orient=\"auto\"><path d=\"M0,0 L0,6 L9,3 z\" fill=\"#475569\"/></marker></defs>
                  <text x=\"40\" y=\"36\" font-size=\"16\" font-weight=\"700\">${input}</text>
                  <text x=\"${targetX}\" y=\"36\" font-size=\"16\" font-weight=\"700\" text-anchor=\"end\">${target}</text>
                  ${edges}
                  ${nodes}
                  <text x=\"40\" y=\"${legendY}\" font-size=\"12\" fill=\"#475569\">Bridge edges: amber · Macro shortcut edges: purple dashed · full labels in SVG titles</text>
                  ${smallGraphMessage}
                </svg>
                """
                .replace("${width}", Integer.toString(WIDTH))
                .replace("${height}", Integer.toString(height))
                .replace("${scenarioId}", escapeXml(evidence.scenarioId()))
                .replace("${evidenceFile}", escapeXml(evidenceFileName))
                .replace("${nodeCount}", Integer.toString(evidence.nodeCount()))
                .replace("${edgeCount}", Integer.toString(evidence.edgeCount()))
                .replace("${input}", escapeXml(shorten(evidence.inputExpression(), 42)))
                .replace("${target}", escapeXml(shorten(evidence.targetExpression(), 54)))
                .replace("${targetX}", Integer.toString(WIDTH - 40))
                .replace("${edges}", edges.toString())
                .replace("${nodes}", nodes.toString())
                .replace("${legendY}", Integer.toString(height - 44))
                .replace("${smallGraphMessage}", smallGraphMessage);
    }

    private Map<String, Point> layout(DiscoveryBenchmarkEvidence evidence, List<DiscoveryBenchmarkEvidence.EvidenceNode> graphNodes) {
        String inputId = canonical(evidence.inputExpression());
        Set<String> targetIds = new LinkedHashSet<>();
        for (DiscoveryBenchmarkEvidence.EvidenceNode node : graphNodes) {
            if ("target".equals(node.kind()) || node.label().equals(evidence.targetExpression())) {
                targetIds.add(node.id());
            }
        }
        Map<String, Integer> levels = new HashMap<>();
        levels.put(inputId, 0);
        for (List<String> path : evidence.foundPaths()) {
            for (int i = 0; i < path.size(); i++) {
                levels.merge(canonical(path.get(i)), i, Math::min);
            }
        }
        int maxLevel = Math.max(1, levels.values().stream().max(Integer::compareTo).orElse(1));
        for (String targetId : targetIds) {
            levels.put(targetId, maxLevel);
        }
        fillMissingLevels(evidence, levels);

        Map<Integer, List<DiscoveryBenchmarkEvidence.EvidenceNode>> byLevel = new LinkedHashMap<>();
        for (DiscoveryBenchmarkEvidence.EvidenceNode node : graphNodes) {
            int level = levels.getOrDefault(node.id(), 1);
            byLevel.computeIfAbsent(level, ignored -> new ArrayList<>()).add(node);
        }
        byLevel.values().forEach(nodes -> nodes.sort(Comparator.comparing(DiscoveryBenchmarkEvidence.EvidenceNode::label)));

        Map<String, Point> positions = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<DiscoveryBenchmarkEvidence.EvidenceNode>> entry : byLevel.entrySet()) {
            int level = entry.getKey();
            int x = LEFT + (int) Math.round((RIGHT - LEFT) * (level / (double) Math.max(1, maxLevel)));
            List<DiscoveryBenchmarkEvidence.EvidenceNode> laneNodes = entry.getValue();
            for (int index = 0; index < laneNodes.size(); index++) {
                int y = TOP + index * LANE_GAP + (level % 2) * 18;
                positions.put(laneNodes.get(index).id(), new Point(x, y));
            }
        }
        return positions;
    }

    private void fillMissingLevels(DiscoveryBenchmarkEvidence evidence, Map<String, Integer> levels) {
        Map<String, List<String>> outgoing = new HashMap<>();
        for (DiscoveryBenchmarkEvidence.EvidenceEdge edge : evidence.edges()) {
            outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
        }
        ArrayDeque<String> queue = new ArrayDeque<>(levels.keySet());
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            int nextLevel = levels.getOrDefault(current, 0) + 1;
            for (String next : outgoing.getOrDefault(current, List.of())) {
                if (!levels.containsKey(next)) {
                    levels.put(next, nextLevel);
                    queue.addLast(next);
                }
            }
        }
    }

    private String roleLabel(DiscoveryBenchmarkEvidence evidence, DiscoveryBenchmarkEvidence.EvidenceNode node) {
        if (node.label().equals(evidence.inputExpression())) {
            return shorten(evidence.inputExpression(), 22);
        }
        if ("target".equals(node.kind()) || node.label().equals(evidence.targetExpression())) {
            return shorten(evidence.targetExpression(), 22);
        }
        return shorten(node.kind(), 22);
    }

    private String strokeFor(String kind) {
        return switch (kind) {
            case "bridge" -> "#d97706";
            case "macro" -> "#7c3aed";
            default -> "#475569";
        };
    }

    private String shorten(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private String canonical(String expression) {
        return expression == null ? "" : expression.replaceAll("\\s+", "");
    }

    private String escapeXml(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record Point(int x, int y) {
    }
}
