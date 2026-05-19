package de.regelsuche.export;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import java.util.stream.Collectors;

public class PlainTextMathRenderer implements MathRenderer {
    @Override
    public String renderExpression(String expression) {
        return expression;
    }

    @Override
    public String renderStep(TransformationStep step) {
        return step.beforeExpression() + " -> " + step.afterExpression() + " [" + step.ruleId() + "]";
    }

    @Override
    public String renderPath(DiscoveredTransformation path) {
        return "Gefundene Umformung\n"
            + "Ausgang: " + path.originalExpression() + "\n"
            + "Rechenweg:\n"
            + path.steps().stream().map(this::renderStep).collect(Collectors.joining("\n")) + "\n"
            + "Verbesserung: " + path.originalExpression() + " -> " + path.improvedExpression() + "\n"
            + "Score: " + path.originalScore().weightedTotal() + " -> " + path.improvedScore().weightedTotal() + "\n"
            + "Status: " + path.validationStatus();
    }
}
