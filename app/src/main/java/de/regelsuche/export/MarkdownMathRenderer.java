package de.regelsuche.export;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.literature.RuleLiteratureCatalog;
import de.regelsuche.scoring.ExpressionScore;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MarkdownMathRenderer implements MathRenderer {
    @Override
    public String renderExpression(String expression) {
        return "$$\n" + expression + "\n$$";
    }

    @Override
    public String renderStep(TransformationStep step) {
        return step.beforeExpression() + " \\rightarrow " + step.afterExpression();
    }

    @Override
    public String renderPath(DiscoveredTransformation path) {
        return renderPath(path, RuleLiteratureCatalog.empty().numbering());
    }

    String renderPath(DiscoveredTransformation path, RuleLiteratureCatalog.Numbering numbering) {
        String numberedSteps = IntStream.range(0, path.steps().size())
            .mapToObj(i -> {
                TransformationStep step = path.steps().get(i);
                return (i + 1) + ". `" + renderStep(step) + "` _(Regel: " + step.ruleId() + ")_"
                    + LiteratureExport.markdownCitations(numbering.forStep(path, step));
            })
            .collect(Collectors.joining("\n"));
        return "### Gefundene Umformung: " + path.originalExpression() + " → " + path.improvedExpression() + "\n\n"
            + "#### Ausgang\n"
            + renderExpression(path.originalExpression()) + "\n\n"
            + "#### Rechenweg\n"
            + (numberedSteps.isEmpty() ? "_keine atomaren Schritte aufgezeichnet_\n" : numberedSteps + "\n") + "\n"
            + "#### Bewertung\n"
            + renderScoreTable(path.originalScore(), path.improvedScore()) + "\n\n"
            + "#### Status\n"
            + path.validationStatus();
    }

    static String renderDocument(List<DiscoveredTransformation> transformations,
            Function<DiscoveredTransformation, String> renderPath) {
        StringBuilder builder = new StringBuilder("# Gefundene Umformungen\n\n");
        if (!transformations.isEmpty()) {
            builder.append("## Inhaltsverzeichnis\n\n");
            for (int i = 0; i < transformations.size(); i++) {
                DiscoveredTransformation transformation = transformations.get(i);
                builder.append(i + 1).append(". ")
                    .append(transformation.originalExpression()).append(" → ")
                    .append(transformation.improvedExpression())
                    .append(" _(Δ=").append(transformation.totalImprovement()).append(", ")
                    .append(transformation.validationStatus()).append(")_\n");
            }
            builder.append('\n');
        }
        for (int i = 0; i < transformations.size(); i++) {
            DiscoveredTransformation transformation = transformations.get(i);
            builder.append("## ").append(i + 1).append(". ")
                .append(transformation.originalExpression()).append(" → ")
                .append(transformation.improvedExpression()).append("\n\n");
            builder.append(renderPath.apply(transformation));
            builder.append("\n\n");
        }
        return builder.toString();
    }

    private String renderScoreTable(ExpressionScore before, ExpressionScore after) {
        return "| Eigenschaft | Vorher | Nachher |\n"
            + "|---|---:|---:|\n"
            + row("String-Länge", before.stringLength(), after.stringLength())
            + row("AST-Knoten", before.astNodeCount(), after.astNodeCount())
            + row("Operatoren", before.operatorCount(), after.operatorCount())
            + row("Verschachtelung", before.nestingDepth(), after.nestingDepth())
            + row("Gewichteter Score", before.weightedTotal(), after.weightedTotal());
    }

    private String row(String label, int before, int after) {
        return "| " + label + " | " + before + " | " + after + " |\n";
    }
}
