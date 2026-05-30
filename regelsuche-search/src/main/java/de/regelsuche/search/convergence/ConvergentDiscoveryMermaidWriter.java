package de.regelsuche.search.convergence;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.util.HashMap;
import java.util.Map;

/** Renders a convergent discovery graph from report data only. */
public final class ConvergentDiscoveryMermaidWriter {
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    public String render(ConvergentDiscoveryReport report) {
        StringBuilder out = new StringBuilder("graph TD\n");
        Map<String, String> nodeIds = new HashMap<>();
        String inputId = nodeId(nodeIds, report.inputExpression());
        out.append("  ").append(inputId).append("[\"").append(escape(report.inputExpression())).append("\"]:::input\n");
        for (ConvergentPath path : report.pathsToTarget()) {
            String previous = inputId;
            for (int i = 1; i < path.expressions().size(); i++) {
                String expression = path.expressions().get(i);
                String current = nodeId(nodeIds, expression);
                out.append("  ").append(current).append("[\"").append(escape(expression)).append("\"]");
                if (i == path.expressions().size() - 1) {
                    out.append(":::convergence");
                } else if (path.pathId().equals(report.convergentStates().getFirst().mostDidacticPathId())) {
                    out.append(":::didactic");
                }
                out.append('\n');
                String rule = path.ruleIds().get(i - 1);
                RuleFamily family = path.ruleFamilies().get(i - 1);
                out.append("  ").append(previous)
                    .append(" -->|").append(escape(family + ": " + rule)).append("| ")
                    .append(current).append('\n');
                previous = current;
            }
        }
        report.convergentStates().stream().findFirst().ifPresent(state -> {
            out.append("  class ").append(nodeId(nodeIds, state.expression())).append(" convergence\n");
            state.macroPathId().ifPresent(pathId -> report.pathsToTarget().stream()
                .filter(path -> path.pathId().equals(pathId))
                .findFirst()
                .ifPresent(path -> out.append("  class ")
                    .append(nodeId(nodeIds, path.expressions().get(Math.max(1, path.expressions().size() - 1))))
                    .append(" macro\n")));
        });
        out.append("  classDef input fill:#eef2ff,stroke:#4338ca,stroke-width:2px\n");
        out.append("  classDef convergence fill:#dcfce7,stroke:#15803d,stroke-width:3px\n");
        out.append("  classDef didactic fill:#fef3c7,stroke:#d97706,stroke-width:2px\n");
        out.append("  classDef macro fill:#e0f2fe,stroke:#0284c7,stroke-width:3px\n");
        return out.toString();
    }

    private String nodeId(Map<String, String> nodeIds, String expression) {
        String hash = canonicalizer.stableHash(expression == null ? "" : expression);
        return nodeIds.computeIfAbsent(hash, ignored -> "conv_" + Integer.toHexString(hash.hashCode()).replace('-', 'n'));
    }

    private String escape(String value) {
        return (value == null ? "" : value).replace("\"", "'");
    }
}
