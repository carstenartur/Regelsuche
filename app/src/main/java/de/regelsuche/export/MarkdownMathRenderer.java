package de.regelsuche.export;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import java.util.stream.Collectors;

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
        String steps = path.steps().stream().map(this::renderStep).collect(Collectors.joining("\n"));
        return "### Gefundene Umformung\n"
            + "Ausgang:\n" + renderExpression(path.originalExpression()) + "\n"
            + "Rechenweg:\n$$\n" + steps + "\n$$\n"
            + "Verbesserung:\n$$\n" + path.originalExpression() + " \\rightarrow " + path.improvedExpression() + "\n$$\n"
            + "Score: " + path.originalScore().weightedTotal() + " → " + path.improvedScore().weightedTotal() + "\n"
            + "Status: " + path.validationStatus();
    }
}
