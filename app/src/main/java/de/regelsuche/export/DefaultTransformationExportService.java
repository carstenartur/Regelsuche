package de.regelsuche.export;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.inventory.ReusableRule;
import java.util.List;
import java.util.stream.Collectors;

public class DefaultTransformationExportService implements TransformationExportService {
    private final MathRenderer markdownRenderer = new MarkdownMathRenderer();
    private final MathRenderer latexRenderer = new LaTeXMathRenderer();

    @Override
    public String exportMarkdown(List<DiscoveredTransformation> transformations) {
        return transformations.stream().map(markdownRenderer::renderPath).collect(Collectors.joining("\n\n"));
    }

    @Override
    public String exportLatex(List<DiscoveredTransformation> transformations) {
        return transformations.stream().map(latexRenderer::renderPath).collect(Collectors.joining("\n\n"));
    }

    @Override
    public String exportJson(List<DiscoveredTransformation> transformations, List<ReusableRule> rules) {
        return "{\n  \"transformations\": ["
            + transformations.stream().map(this::transformationJson).collect(Collectors.joining(","))
            + "\n  ],\n  \"rules\": ["
            + rules.stream().map(this::ruleJson).collect(Collectors.joining(","))
            + "\n  ]\n}";
    }

    @Override
    public String exportMermaid(List<DiscoveredTransformation> transformations) {
        StringBuilder builder = new StringBuilder("graph TD\n");
        int nodeIndex = 0;
        for (DiscoveredTransformation transformation : transformations) {
            for (TransformationStep step : transformation.steps()) {
                String from = "N" + nodeIndex++;
                String to = "N" + nodeIndex++;
                builder.append("  ").append(from).append("[\"").append(escapeMermaid(step.beforeExpression())).append("\"]")
                    .append(" -->|").append(escapeMermaid(step.ruleId())).append("| ")
                    .append(to).append("[\"").append(escapeMermaid(step.afterExpression())).append("\"]\n");
            }
        }
        return builder.toString();
    }

    private String transformationJson(DiscoveredTransformation transformation) {
        return "\n    {\"id\":\"" + escapeJson(transformation.id()) + "\","
            + "\"originalExpression\":\"" + escapeJson(transformation.originalExpression()) + "\","
            + "\"improvedExpression\":\"" + escapeJson(transformation.improvedExpression()) + "\","
            + "\"totalImprovement\":" + transformation.totalImprovement() + ","
            + "\"validationStatus\":\"" + transformation.validationStatus() + "\"}";
    }

    private String ruleJson(ReusableRule rule) {
        return "\n    {\"id\":\"" + escapeJson(rule.id()) + "\","
            + "\"leftPattern\":\"" + escapeJson(rule.leftPattern()) + "\","
            + "\"rightPattern\":\"" + escapeJson(rule.rightPattern()) + "\","
            + "\"proofStatus\":\"" + rule.proofStatus() + "\"}";
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private String escapeMermaid(String value) {
        return value.replace("\"", "'");
    }
}
