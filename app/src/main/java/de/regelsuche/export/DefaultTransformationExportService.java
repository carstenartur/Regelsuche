package de.regelsuche.export;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.scoring.ExpressionScore;
import java.time.Clock;
import java.util.List;
import java.util.stream.Collectors;

public class DefaultTransformationExportService implements TransformationExportService {
    private final MathRenderer markdownRenderer = new MarkdownMathRenderer();
    private final MathRenderer latexRenderer = new LaTeXMathRenderer();
    private final Clock clock;

    public DefaultTransformationExportService() {
        this(Clock.systemUTC());
    }

    public DefaultTransformationExportService(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String exportMarkdown(List<DiscoveredTransformation> transformations) {
        return MarkdownMathRenderer.renderDocument(transformations, markdownRenderer);
    }

    @Override
    public String exportLatex(List<DiscoveredTransformation> transformations) {
        return transformations.stream().map(latexRenderer::renderPath).collect(Collectors.joining("\n\n"));
    }

    @Override
    public String exportJson(List<DiscoveredTransformation> transformations, List<ReusableRule> rules) {
        return exportJson(transformations, List.of(), rules);
    }

    @Override
    public String exportJson(
        List<DiscoveredTransformation> transformations,
        List<RuleCandidate> candidates,
        List<ReusableRule> rules
    ) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("schemaVersion", ExportBundle.CURRENT_SCHEMA_VERSION);
        writer.property("generatedAt", clock.instant().toString());
        writer.array("transformations", w -> transformations.forEach(transformation ->
            w.objectValue(inner -> writeTransformation(inner, transformation))));
        writer.array("ruleCandidates", w -> candidates.forEach(candidate ->
            w.objectValue(inner -> writeCandidate(inner, candidate))));
        writer.array("reusableRules", w -> rules.forEach(rule ->
            w.objectValue(inner -> writeRule(inner, rule))));
        writer.endObject();
        return writer.toString();
    }

    @Override
    public String exportBundle(ExportBundle bundle) {
        return exportJson(bundle.transformations(), bundle.ruleCandidates(), bundle.reusableRules());
    }

    @Override
    public String exportMermaid(List<DiscoveredTransformation> transformations) {
        StringBuilder builder = new StringBuilder("graph TD\n");
        for (DiscoveredTransformation transformation : transformations) {
            String nodePrefix = "P" + Math.abs(transformation.id().hashCode()) + "_";
            int stepIndex = 0;
            for (TransformationStep step : transformation.steps()) {
                String from = nodePrefix + stepIndex;
                String to = nodePrefix + (stepIndex + 1);
                builder.append("  ").append(from).append("[\"").append(escapeMermaid(step.beforeExpression())).append("\"]")
                    .append(" -->|").append(escapeMermaid(step.ruleId())).append("| ")
                    .append(to).append("[\"").append(escapeMermaid(step.afterExpression())).append("\"]\n");
                stepIndex++;
            }
        }
        return builder.toString();
    }

    private void writeTransformation(JsonWriter writer, DiscoveredTransformation transformation) {
        writer.property("id", transformation.id());
        writer.property("originalExpression", transformation.originalExpression());
        writer.property("improvedExpression", transformation.improvedExpression());
        writer.object("scores", scores -> {
            scores.object("original", original -> writeScore(original, transformation.originalScore()));
            scores.object("improved", improved -> writeScore(improved, transformation.improvedScore()));
        });
        writer.property("totalImprovement", transformation.totalImprovement());
        writer.property("validationStatus", transformation.validationStatus().name());
        writer.property("discoveredAt", transformation.discoveredAt().toString());
        writer.property("canonicalHash", transformation.canonicalHash());
        writer.array("steps", steps -> transformation.steps().forEach(step ->
            steps.objectValue(inner -> writeStep(inner, step))));
    }

    private void writeStep(JsonWriter writer, TransformationStep step) {
        writer.property("index", step.index());
        writer.property("beforeExpression", step.beforeExpression());
        writer.property("afterExpression", step.afterExpression());
        writer.property("ruleId", step.ruleId());
        writer.property("ruleKind", step.ruleKind().name());
        writer.property("scoreBefore", step.scoreBefore());
        writer.property("scoreAfter", step.scoreAfter());
        writer.property("equivalencePreserving", step.equivalencePreserving());
        writer.property("explanation", step.explanation());
    }

    private void writeScore(JsonWriter writer, ExpressionScore score) {
        writer.property("stringLength", score.stringLength());
        writer.property("astNodeCount", score.astNodeCount());
        writer.property("operatorCount", score.operatorCount());
        writer.property("nestingDepth", score.nestingDepth());
        writer.property("recognizedPatternBonus", score.recognizedPatternBonus());
        writer.property("weightedTotal", score.weightedTotal());
    }

    private void writeRule(JsonWriter writer, ReusableRule rule) {
        writer.property("id", rule.id());
        writer.property("leftPattern", rule.leftPattern());
        writer.property("rightPattern", rule.rightPattern());
        writer.stringArray("parameterRelations", rule.parameterRelations());
        writer.property("proofStatus", rule.proofStatus().name());
        writer.property("knownRuleStatus", rule.knownRuleStatus().name());
        writer.property("supportingExamples", rule.supportingExamples());
        writer.property("averageImprovement", rule.averageImprovement());
        writer.property("createdAt", rule.createdAt().toString());
        writer.property("canonicalHash", rule.canonicalHash());
        if (rule.lastUsedAt() == null) {
            writer.nullProperty("lastUsedAt");
        } else {
            writer.property("lastUsedAt", rule.lastUsedAt().toString());
        }
        writer.property("usageCount", rule.usageCount());
    }

    private void writeCandidate(JsonWriter writer, RuleCandidate candidate) {
        writer.property("leftPattern", candidate.leftPattern());
        writer.property("rightPattern", candidate.rightPattern());
        writer.property("examplesCount", candidate.examplesCount());
        writer.property("averageScoreImprovement", candidate.averageScoreImprovement());
        writer.property("maximumScoreImprovement", candidate.maximumScoreImprovement());
        writer.property("equivalenceVerified", candidate.equivalenceVerified());
        writer.property("generalizationPlausible", candidate.generalizationPlausible());
        writer.property("containsFreeParameters", candidate.containsFreeParameters());
        writer.stringArray("parameterRelations", candidate.parameterRelations());
        writer.property("status", candidate.status().name());
        writer.property("proofStatus", candidate.proofStatus().name());
        writer.property("canonicalHash", candidate.canonicalHash());
        writer.stringArray("supportingTransformationIds", candidate.supportingTransformationIds());
    }

    private String escapeMermaid(String value) {
        return value.replace("\"", "'");
    }
}
