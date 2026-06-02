package de.regelsuche.docs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SearchSpaceGallerySvgWriter {
    private static final int WIDTH = 1080;
    private static final int LEFT = 110;
    private static final int RIGHT = 980;
    private static final int TOP = 120;
    private static final int LANE_GAP = 76;
    private static final int MIN_VISIBLE_NODES = 10;
    private static final int MAX_VISIBLE_NODES = 30;

    public String write(DiscoveryBenchmarkEvidence evidence, String evidenceFileName) {
        List<DiscoveryBenchmarkEvidence.EvidenceNode> graphNodes = selectVisibleNodes(evidence);
        Set<String> visibleNodeIds = graphNodes.stream().map(DiscoveryBenchmarkEvidence.EvidenceNode::id).collect(java.util.stream.Collectors.toSet());
        List<DiscoveryBenchmarkEvidence.EvidenceEdge> visibleEdges = evidence.edges().stream()
                .filter(edge -> visibleNodeIds.contains(edge.from()) && visibleNodeIds.contains(edge.to()))
                .toList();
        Map<String, Point> positions = layout(graphNodes);

        StringBuilder edges = new StringBuilder();
        for (DiscoveryBenchmarkEvidence.EvidenceEdge edge : visibleEdges) {
            Point from = positions.get(edge.from());
            Point to = positions.get(edge.to());
            if (from == null || to == null) {
                continue;
            }
            boolean selected = hasTag(edge.tags(), "selected-path");
            boolean macroShortcut = "macro".equals(edge.kind()) || hasTag(edge.tags(), "macro-shortcut");
            String stroke = strokeFor(edge.kind(), selected);
            int yOffset = macroShortcut ? -32 : 0;
            String cssClass = "edge " + escapeXml(edge.kind()) + (selected ? " selected" : " alternative");
            edges.append("<path class=\"").append(cssClass).append("\" d=\"M ").append(from.x() + 46).append(' ').append(from.y() + yOffset)
                    .append(" C ").append((from.x() + to.x()) / 2).append(' ').append(from.y() + yOffset - 24)
                    .append(", ").append((from.x() + to.x()) / 2).append(' ').append(to.y() + yOffset - 24)
                    .append(", ").append(to.x() - 46).append(' ').append(to.y() + yOffset)
                    .append("\" fill=\"none\" stroke=\"").append(stroke).append("\" stroke-width=\"")
                    .append(selected ? 3.2 : macroShortcut ? 2.8 : 1.8)
                    .append("\" marker-end=\"url(#arrow)\"");
            if (macroShortcut) {
                edges.append(" stroke-dasharray=\"8 5\"");
            } else if (!selected) {
                edges.append(" stroke-opacity=\"0.7\"");
            }
            edges.append("><title>").append(escapeXml(edge.ruleId())).append(" · ").append(escapeXml(edge.source()))
                    .append(" · ").append(escapeXml(edge.packId())).append("</title></path>\n");
            edges.append("<text x=\"").append((from.x() + to.x()) / 2 - 54).append("\" y=\"")
                    .append((from.y() + to.y()) / 2 + yOffset - 8)
                    .append("\" font-size=\"9\" fill=\"").append(stroke).append("\"")
                    .append(selected ? " font-weight=\"700\"" : "")
                    .append(">")
                    .append(escapeXml(shorten(edge.ruleId(), 30))).append("<title>")
                    .append(escapeXml(edge.ruleId())).append("</title></text>\n");
        }

        StringBuilder nodes = new StringBuilder();
        for (DiscoveryBenchmarkEvidence.EvidenceNode node : graphNodes) {
            Point point = positions.get(node.id());
            if (point == null) {
                continue;
            }
            boolean isInput = "input".equals(node.kind()) || hasTag(node.tags(), "input");
            boolean isTarget = "target".equals(node.kind()) || hasTag(node.tags(), "target");
            boolean selected = hasTag(node.tags(), "selected-path");
            boolean deadEnd = hasTag(node.tags(), "dead-end");
            String fill = isTarget ? "#dcfce7" : isInput ? "#dbeafe" : selected ? "#ede9fe" : "#f8fafc";
            String stroke = isTarget ? "#16a34a" : isInput ? "#2563eb" : selected ? "#7c3aed" : deadEnd ? "#ef4444" : "#334155";
            nodes.append("<g class=\"node ").append(escapeXml(node.kind())).append(selected ? " selected" : " alternative")
                    .append(deadEnd ? " dead-end" : "").append("\">");
            nodes.append("<rect id=\"").append(escapeXml(node.id())).append("\" x=\"").append(point.x() - 60)
                    .append("\" y=\"").append(point.y() - 24)
                    .append("\" width=\"120\" height=\"48\" rx=\"12\" fill=\"").append(fill)
                    .append("\" stroke=\"").append(stroke).append("\" stroke-width=\"").append(selected ? 2.8 : 2).append("\"/>");
            nodes.append("<title>").append(escapeXml(node.label())).append("</title>");
            nodes.append("<text x=\"").append(point.x() - 52).append("\" y=\"").append(point.y() - 2)
                    .append("\" font-size=\"10\">").append(escapeXml(shorten(node.label(), 24))).append("</text>");
            nodes.append("<text x=\"").append(point.x() - 52).append("\" y=\"").append(point.y() + 13)
                    .append("\" font-size=\"9\" fill=\"#64748b\">").append(escapeXml(roleLabel(node))).append("</text>");
            nodes.append("</g>\n");
        }

        int maxY = positions.values().stream().mapToInt(Point::y).max().orElse(TOP);
        int height = Math.max(340, maxY + 170);
        String smallGraphMessage = evidence.smallGraphMessage() == null || evidence.smallGraphMessage().isBlank()
                ? ""
                : "<text x=\"40\" y=\"" + (height - 24) + "\" font-size=\"12\">" + escapeXml(evidence.smallGraphMessage()) + "</text>";
        String stats = "<text x=\"40\" y=\"" + (height - 48) + "\" font-size=\"12\" fill=\"#475569\">"
                + "Visible: " + graphNodes.size() + "/" + evidence.nodeCount()
                + " nodes · " + visibleEdges.size() + "/" + evidence.edgeCount() + " edges</text>";

        return """
                <svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${width}\" height=\"${height}\" viewBox=\"0 0 ${width} ${height}\" data-generated-by=\"SearchSpaceGallerySvgWriter\" data-scenario-id=\"${scenarioId}\" data-evidence=\"${evidenceFile}\" data-node-count=\"${nodeCount}\" data-edge-count=\"${edgeCount}\">
                  <rect width=\"100%\" height=\"100%\" fill=\"#f8fafc\"/>
                  <defs><marker id=\"arrow\" markerWidth=\"10\" markerHeight=\"10\" refX=\"10\" refY=\"3\" orient=\"auto\"><path d=\"M0,0 L0,6 L9,3 z\" fill=\"#475569\"/></marker></defs>
                  <text x=\"40\" y=\"36\" font-size=\"16\" font-weight=\"700\">${input}</text>
                  <text x=\"${targetX}\" y=\"36\" font-size=\"16\" font-weight=\"700\" text-anchor=\"end\">${target}</text>
                  ${edges}
                  ${nodes}
                  <text x=\"40\" y=\"${legendY}\" font-size=\"12\" fill=\"#475569\">Selected path: violet · Alternatives: slate · Bridge edges: amber · Macro shortcut: purple dashed · Dead ends: red border</text>
                  ${stats}
                  ${smallGraphMessage}
                </svg>
                """
                .replace("${width}", Integer.toString(WIDTH))
                .replace("${height}", Integer.toString(height))
                .replace("${scenarioId}", escapeXml(evidence.scenarioId()))
                .replace("${evidenceFile}", escapeXml(evidenceFileName))
                .replace("${nodeCount}", Integer.toString(evidence.nodeCount()))
                .replace("${edgeCount}", Integer.toString(evidence.edgeCount()))
                .replace("${input}", escapeXml(shorten(evidence.inputExpression(), 46)))
                .replace("${target}", escapeXml(shorten(evidence.targetExpression(), 60)))
                .replace("${targetX}", Integer.toString(WIDTH - 40))
                .replace("${edges}", edges.toString())
                .replace("${nodes}", nodes.toString())
                .replace("${legendY}", Integer.toString(height - 70))
                .replace("${stats}", stats)
                .replace("${smallGraphMessage}", smallGraphMessage);
    }

    private List<DiscoveryBenchmarkEvidence.EvidenceNode> selectVisibleNodes(DiscoveryBenchmarkEvidence evidence) {
        List<DiscoveryBenchmarkEvidence.EvidenceNode> all = evidence.nodes();
        if (all.size() <= MAX_VISIBLE_NODES) {
            return all;
        }
        Map<String, DiscoveryBenchmarkEvidence.EvidenceNode> byId = new LinkedHashMap<>();
        for (DiscoveryBenchmarkEvidence.EvidenceNode node : all) {
            byId.put(node.id(), node);
        }
        LinkedHashSet<DiscoveryBenchmarkEvidence.EvidenceNode> required = new LinkedHashSet<>();
        for (DiscoveryBenchmarkEvidence.EvidenceNode node : all) {
            if (hasTag(node.tags(), "selected-path") || "input".equals(node.kind()) || "target".equals(node.kind())) {
                required.add(node);
            }
        }
        for (DiscoveryBenchmarkEvidence.EvidenceEdge edge : evidence.edges()) {
            if ("bridge".equals(edge.kind()) || "macro".equals(edge.kind())) {
                DiscoveryBenchmarkEvidence.EvidenceNode from = byId.get(edge.from());
                DiscoveryBenchmarkEvidence.EvidenceNode to = byId.get(edge.to());
                if (from != null) {
                    required.add(from);
                }
                if (to != null) {
                    required.add(to);
                }
                if (required.size() >= MIN_VISIBLE_NODES) {
                    break;
                }
            }
        }
        List<DiscoveryBenchmarkEvidence.EvidenceNode> ranked = new ArrayList<>(all);
        ranked.sort(Comparator
                .comparing((DiscoveryBenchmarkEvidence.EvidenceNode node) -> !hasTag(node.tags(), "selected-path"))
                .thenComparing(node -> "target".equals(node.kind()) ? 0 : "input".equals(node.kind()) ? 1 : 2)
                .thenComparingInt(DiscoveryBenchmarkEvidence.EvidenceNode::depth)
                .thenComparing(DiscoveryBenchmarkEvidence.EvidenceNode::label));
        int preferredVisible = Math.max(MIN_VISIBLE_NODES, Math.min(MAX_VISIBLE_NODES, ranked.size()));
        List<DiscoveryBenchmarkEvidence.EvidenceNode> selected = new ArrayList<>(required);
        for (DiscoveryBenchmarkEvidence.EvidenceNode node : ranked) {
            if (selected.size() >= preferredVisible) {
                break;
            }
            if (!selected.contains(node)) {
                selected.add(node);
            }
        }
        selected.sort(Comparator.comparingInt(DiscoveryBenchmarkEvidence.EvidenceNode::depth).thenComparing(DiscoveryBenchmarkEvidence.EvidenceNode::label));
        return selected;
    }

    private Map<String, Point> layout(List<DiscoveryBenchmarkEvidence.EvidenceNode> graphNodes) {
        Map<Integer, List<DiscoveryBenchmarkEvidence.EvidenceNode>> byLevel = new LinkedHashMap<>();
        for (DiscoveryBenchmarkEvidence.EvidenceNode node : graphNodes) {
            int level = Math.max(0, node.depth());
            byLevel.computeIfAbsent(level, ignored -> new ArrayList<>()).add(node);
        }
        byLevel.values().forEach(nodes -> nodes.sort(Comparator.comparing(DiscoveryBenchmarkEvidence.EvidenceNode::label)));
        int maxLevel = byLevel.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);

        Map<String, Point> positions = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<DiscoveryBenchmarkEvidence.EvidenceNode>> entry : byLevel.entrySet()) {
            int level = entry.getKey();
            int x = LEFT + (int) Math.round((RIGHT - LEFT) * (level / (double) Math.max(1, maxLevel)));
            List<DiscoveryBenchmarkEvidence.EvidenceNode> laneNodes = entry.getValue();
            for (int index = 0; index < laneNodes.size(); index++) {
                int y = TOP + index * LANE_GAP + (level % 2) * 14;
                positions.put(laneNodes.get(index).id(), new Point(x, y));
            }
        }
        return positions;
    }

    private String roleLabel(DiscoveryBenchmarkEvidence.EvidenceNode node) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        if ("input".equals(node.kind()) || hasTag(node.tags(), "input")) {
            labels.add("input");
        }
        if ("target".equals(node.kind()) || hasTag(node.tags(), "target")) {
            labels.add("target");
        }
        if (hasTag(node.tags(), "selected-path")) {
            labels.add("selected");
        }
        if (hasTag(node.tags(), "dead-end")) {
            labels.add("dead-end");
        }
        if (labels.isEmpty()) {
            labels.add("alternative");
        }
        return String.join(" · ", labels);
    }

    private boolean hasTag(List<String> tags, String tag) {
        return tags != null && tags.contains(tag);
    }

    private String strokeFor(String kind, boolean selectedPath) {
        if ("bridge".equals(kind)) {
            return selectedPath ? "#b45309" : "#d97706";
        }
        if ("macro".equals(kind)) {
            return selectedPath ? "#6d28d9" : "#7c3aed";
        }
        return selectedPath ? "#5b21b6" : "#475569";
    }

    private String shorten(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private String escapeXml(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record Point(int x, int y) {
    }
}
