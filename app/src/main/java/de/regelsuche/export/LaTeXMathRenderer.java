package de.regelsuche.export;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import java.util.stream.Collectors;

public class LaTeXMathRenderer implements MathRenderer {
    @Override
    public String renderExpression(String expression) {
        return toLatex(expression);
    }

    @Override
    public String renderStep(TransformationStep step) {
        return toLatex(step.beforeExpression()) + " \\rightarrow " + toLatex(step.afterExpression());
    }

    @Override
    public String renderPath(DiscoveredTransformation path) {
        String steps = path.steps().stream().map(this::renderStep).collect(Collectors.joining(" \\\\n"));
        return "\\begin{align*}\n"
            + steps + "\\\\\n"
            + "\\text{Score: } " + path.originalScore().weightedTotal() + " &\\rightarrow " + path.improvedScore().weightedTotal() + "\\\\\n"
            + "\\text{Status: } " + path.validationStatus() + "\n"
            + "\\end{align*}";
    }

    private String toLatex(String expression) {
        return expression.replace("*", "\\cdot ").replace("^", "^");
    }
}
