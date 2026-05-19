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
            + "\n  ],\n  \"reusableRules\": ["
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
            + "\"scores\":{\"original\":" + scoreJson(transformation.originalScore())
            + ",\"improved\":" + scoreJson(transformation.improvedScore()) + "},"
            + "\"totalImprovement\":" + transformation.totalImprovement() + ","
            + "\"validationStatus\":\"" + transformation.validationStatus() + "\","
            + "\"discoveredAt\":\"" + transformation.discoveredAt() + "\","
            + "\"canonicalHash\":\"" + escapeJson(transformation.canonicalHash()) + "\","
            + "\"steps\":["
            + transformation.steps().stream().map(this::stepJson).collect(Collectors.joining(","))
            + "]}";
    }

    private String ruleJson(ReusableRule rule) {
        return "\n    {\"id\":\"" + escapeJson(rule.id()) + "\","
            + "\"leftPattern\":\"" + escapeJson(rule.leftPattern()) + "\","
            + "\"rightPattern\":\"" + escapeJson(rule.rightPattern()) + "\","
            + "\"parameterRelations\":["
            + rule.parameterRelations().stream()
                .map(relation -> "\"" + escapeJson(relation) + "\"")
                .collect(Collectors.joining(","))
            + "],"
            + "\"proofStatus\":\"" + rule.proofStatus() + "\","
            + "\"knownRuleStatus\":\"" + rule.knownRuleStatus() + "\","
            + "\"supportingExamples\":" + rule.supportingExamples() + ","
            + "\"averageImprovement\":" + rule.averageImprovement() + ","
            + "\"createdAt\":\"" + rule.createdAt() + "\"}";
    }

    private String stepJson(TransformationStep step) {
        return "{\"index\":" + step.index() + ","
            + "\"beforeExpression\":\"" + escapeJson(step.beforeExpression()) + "\","
            + "\"afterExpression\":\"" + escapeJson(step.afterExpression()) + "\","
            + "\"ruleId\":\"" + escapeJson(step.ruleId()) + "\","
            + "\"ruleKind\":\"" + step.ruleKind() + "\","
            + "\"scoreBefore\":" + step.scoreBefore() + ","
            + "\"scoreAfter\":" + step.scoreAfter() + ","
            + "\"equivalencePreserving\":" + step.equivalencePreserving() + ","
            + "\"explanation\":\"" + escapeJson(step.explanation()) + "\"}";
    }

    private String scoreJson(de.regelsuche.scoring.ExpressionScore score) {
        return "{\"stringLength\":" + score.stringLength() + ","
            + "\"astNodeCount\":" + score.astNodeCount() + ","
            + "\"operatorCount\":" + score.operatorCount() + ","
            + "\"nestingDepth\":" + score.nestingDepth() + ","
            + "\"recognizedPatternBonus\":" + score.recognizedPatternBonus() + ","
            + "\"weightedTotal\":" + score.weightedTotal() + "}";
    }

    private String escapeJson(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private String escapeMermaid(String value) {
        return value.replace("\"", "'");
    }
}
