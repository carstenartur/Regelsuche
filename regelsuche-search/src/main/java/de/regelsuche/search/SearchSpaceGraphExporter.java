package de.regelsuche.search;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SearchSpaceGraphExporter {
    public String toDot(List<ProofStep> transitions, SearchGraphStyle style) {
        Set<String> nodes = new LinkedHashSet<>();
        for (ProofStep step : transitions) {
            nodes.add(step.from());
            nodes.add(step.to());
        }
        SearchGraphStyle graphStyle = style == null ? SearchGraphStyle.empty() : style;
        StringBuilder dot = new StringBuilder("digraph SearchSpace {\n");
        for (String node : nodes) {
            dot.append("  \"").append(escape(node)).append("\" [style=filled, fillcolor=\"")
                    .append(color(node, graphStyle)).append("\"];\n");
        }
        for (ProofStep step : transitions) {
            dot.append("  \"").append(escape(step.from())).append("\" -> \"")
                    .append(escape(step.to())).append("\" [label=\"")
                    .append(escape(step.ruleId())).append("\"];\n");
        }
        return dot.append("}\n").toString();
    }

    private String color(String node, SearchGraphStyle style) {
        if (style.discoveryPath().contains(node)) {
            return "#c8f7c5";
        }
        if (style.macroPath().contains(node)) {
            return "#c7d7ff";
        }
        if (style.convergenceNodes().contains(node)) {
            return "#ffe082";
        }
        if (style.deadEnds().contains(node)) {
            return "#ffcdd2";
        }
        return "#eeeeee";
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
