package de.regelsuche.export;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import java.util.List;

public class LaTeXMathRenderer implements MathRenderer {
    @Override
    public String renderExpression(String expression) {
        return toLatex(expression);
    }

    @Override
    public String renderStep(TransformationStep step) {
        return toLatex(step.beforeExpression()) + " &\\rightarrow " + toLatex(step.afterExpression());
    }

    @Override
    public String renderPath(DiscoveredTransformation path) {
        StringBuilder body = new StringBuilder();
        body.append("\\begin{align*}\n");
        List<TransformationStep> steps = path.steps();
        if (steps.isEmpty()) {
            body.append(toLatex(path.originalExpression()))
                .append(" &\\rightarrow ")
                .append(toLatex(path.improvedExpression()))
                .append(" \\tag{0}\\\\\n");
        } else {
            body.append(toLatex(steps.get(0).beforeExpression()))
                .append(" &\\rightarrow ")
                .append(toLatex(steps.get(0).afterExpression()))
                .append(" && \\text{(")
                .append(escapeText(steps.get(0).ruleId()))
                .append(")} \\tag{1}\\\\\n");
            for (int i = 1; i < steps.size(); i++) {
                body.append("        &\\rightarrow ")
                    .append(toLatex(steps.get(i).afterExpression()))
                    .append(" && \\text{(")
                    .append(escapeText(steps.get(i).ruleId()))
                    .append(")} \\tag{")
                    .append(i + 1)
                    .append("}\\\\\n");
            }
        }
        body.append("\\end{align*}\n");
        body.append("\\textbf{Score:} ")
            .append(path.originalScore().weightedTotal())
            .append(" \\to ")
            .append(path.improvedScore().weightedTotal())
            .append("\\\\\n");
        body.append("\\textbf{Status:} ").append(path.validationStatus());
        return body.toString();
    }

    private String toLatex(String expression) {
        return expression.replace("*", " \\cdot ");
    }

    private String escapeText(String value) {
        return value.replace("_", "\\_");
    }
}
