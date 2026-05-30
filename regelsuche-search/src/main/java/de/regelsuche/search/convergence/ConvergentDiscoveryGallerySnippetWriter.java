package de.regelsuche.search.convergence;

import java.util.stream.Collectors;

/** Markdown snippet writer for generated convergent discovery gallery evidence. */
public final class ConvergentDiscoveryGallerySnippetWriter {
    public String render(ConvergentDiscoveryReport report) {
        StringBuilder out = new StringBuilder();
        out.append("### Convergent discovery: multiple paths to one result\n\n");
        out.append("- input: `").append(report.inputExpression()).append("`\n");
        out.append("- target: `").append(report.canonicalTargetExpression()).append("`\n");
        out.append("- number of distinct paths: ").append(report.pathsToTarget().size()).append('\n');
        out.append("- path families: ").append(report.ruleFamiliesUsed()).append('\n');
        report.convergentStates().stream().findFirst().ifPresent(state -> {
            out.append("- shortest path: ").append(labelForPathId(report, state.shortestPathId())).append('\n');
            out.append("- most didactic path: ").append(labelForPathId(report, state.mostDidacticPathId())).append('\n');
            state.macroPathId().ifPresent(path -> out.append("- macro shortcut path: ")
                .append(labelForPathId(report, path)).append('\n'));
        });
        out.append("- validation status: ")
            .append(report.pathsToTarget().stream()
                .map(ConvergentPath::validationStatus)
                .distinct()
                .collect(Collectors.joining(", ")))
            .append('\n');
        String sourceReplayIds = report.pathsToTarget().stream()
            .flatMap(path -> path.sourceReplayIds().stream())
            .distinct()
            .collect(Collectors.joining(", "));
        if (!sourceReplayIds.isBlank()) {
            out.append("- source replay ids: ").append(sourceReplayIds).append('\n');
        }
        out.append('\n');
        int index = 1;
        for (ConvergentPath path : report.pathsToTarget()) {
            out.append("#### Path ").append(index++).append(": ").append(labelForPath(path)).append("\n\n");
            out.append("- rules: `").append(String.join(" -> ", path.ruleIds())).append("`\n");
            out.append("- families: ").append(path.ruleFamilies()).append('\n');
            out.append("- length: ").append(path.length()).append('\n');
            out.append("- proofStatus: ").append(path.proofStatus()).append("\n\n");
        }
        return out.toString();
    }

    private String labelForPathId(ConvergentDiscoveryReport report, String pathId) {
        return report.pathsToTarget().stream()
            .filter(path -> path.pathId().equals(pathId))
            .findFirst()
            .map(this::labelForPath)
            .orElse("selected gallery path");
    }

    private String labelForPath(ConvergentPath path) {
        if (path.containsMacroStep()) {
            return "learned macro shortcut";
        }
        if (path.ruleFamilies().contains(RuleFamily.HIDDEN_STRUCTURE)
            && path.length() > nonNormalizationRuleCount(path)) {
            return "expanded discovery variant";
        }
        if (path.ruleFamilies().contains(RuleFamily.HIDDEN_STRUCTURE)) {
            return "hidden-structure discovery";
        }
        if (path.ruleFamilies().contains(RuleFamily.EXPANSION)) {
            return "expanded discovery variant";
        }
        return "discovery path";
    }

    private int nonNormalizationRuleCount(ConvergentPath path) {
        return (int) path.ruleFamilies().stream()
            .filter(family -> family != RuleFamily.NORMALIZATION && family != RuleFamily.OTHER)
            .count();
    }
}
