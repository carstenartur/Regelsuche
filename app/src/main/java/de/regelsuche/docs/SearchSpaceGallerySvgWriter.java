package de.regelsuche.docs;

import java.util.List;

public final class SearchSpaceGallerySvgWriter {
    public String write(DiscoveryBenchmarkEvidence evidence, String evidenceFileName) {
        StringBuilder nodes = new StringBuilder();
        StringBuilder edges = new StringBuilder();
        List<DiscoveryBenchmarkEvidence.EvidenceNode> graphNodes = evidence.nodes();
        for (int i = 0; i < graphNodes.size(); i++) {
            DiscoveryBenchmarkEvidence.EvidenceNode node = graphNodes.get(i);
            int x = 80 + (i % 5) * 165;
            int y = 90 + (i / 5) * 125;
            String fill = "target".equals(node.kind()) ? "#dcfce7" : "#e0f2fe";
            nodes.append("<circle id=\"").append(escapeXml(node.id())).append("\" cx=\"").append(x).append("\" cy=\"").append(y)
                    .append("\" r=\"34\" fill=\"").append(fill).append("\" stroke=\"#334155\"/>\n");
            nodes.append("<text x=\"").append(x - 60).append("\" y=\"").append(y + 5)
                    .append("\" font-size=\"10\">").append(escapeXml(node.label())).append("</text>\n");
        }
        for (DiscoveryBenchmarkEvidence.EvidenceEdge edge : evidence.edges()) {
            int from = indexOf(graphNodes, edge.from());
            int to = indexOf(graphNodes, edge.to());
            if (from < 0 || to < 0) {
                continue;
            }
            int x1 = 80 + (from % 5) * 165;
            int y1 = 90 + (from / 5) * 125;
            int x2 = 80 + (to % 5) * 165;
            int y2 = 90 + (to / 5) * 125;
            String stroke = switch (edge.kind()) {
                case "bridge" -> "#d97706";
                case "macro" -> "#7c3aed";
                default -> "#475569";
            };
            edges.append("<line x1=\"").append(x1 + 34).append("\" y1=\"").append(y1)
                    .append("\" x2=\"").append(x2 - 34).append("\" y2=\"").append(y2)
                    .append("\" stroke=\"").append(stroke).append("\" marker-end=\"url(#arrow)\"/>\n");
            edges.append("<text x=\"").append((x1 + x2) / 2 - 45).append("\" y=\"").append((y1 + y2) / 2 - 8)
                    .append("\" font-size=\"9\">").append(escapeXml(edge.ruleId())).append("</text>\n");
        }
        int rows = Math.max(1, (graphNodes.size() + 4) / 5);
        int height = Math.max(260, rows * 125 + 95);
        String smallGraphMessage = evidence.smallGraphMessage() == null || evidence.smallGraphMessage().isBlank()
                ? ""
                : "<text x=\"40\" y=\"" + (height - 20) + "\" font-size=\"12\">" + escapeXml(evidence.smallGraphMessage()) + "</text>";
        return """
                <svg xmlns=\"http://www.w3.org/2000/svg\" width=\"900\" height=\"${height}\" viewBox=\"0 0 900 ${height}\" data-generated-by=\"SearchSpaceGallerySvgWriter\" data-scenario-id=\"${scenarioId}\" data-evidence=\"${evidenceFile}\" data-node-count=\"${nodeCount}\" data-edge-count=\"${edgeCount}\">
                  <rect width=\"100%\" height=\"100%\" fill=\"#f8fafc\"/>
                  <defs><marker id=\"arrow\" markerWidth=\"10\" markerHeight=\"10\" refX=\"10\" refY=\"3\" orient=\"auto\"><path d=\"M0,0 L0,6 L9,3 z\" fill=\"#475569\"/></marker></defs>
                  ${edges}
                  ${nodes}
                  <text x=\"40\" y=\"${legendY}\" font-size=\"12\">Bridge rules: amber · Macro rules: purple · Target: green</text>
                  ${smallGraphMessage}
                </svg>
                """
                .replace("${height}", Integer.toString(height))
                .replace("${scenarioId}", escapeXml(evidence.scenarioId()))
                .replace("${evidenceFile}", escapeXml(evidenceFileName))
                .replace("${nodeCount}", Integer.toString(evidence.nodeCount()))
                .replace("${edgeCount}", Integer.toString(evidence.edgeCount()))
                .replace("${edges}", edges.toString())
                .replace("${nodes}", nodes.toString())
                .replace("${legendY}", Integer.toString(height - 40))
                .replace("${smallGraphMessage}", smallGraphMessage);
    }

    private int indexOf(List<DiscoveryBenchmarkEvidence.EvidenceNode> nodes, String id) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private String escapeXml(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
