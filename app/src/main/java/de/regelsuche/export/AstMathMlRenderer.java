package de.regelsuche.export;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;

/**
 * AST-based MathML renderer.
 *
 * <p>Companion to {@link AstLatexRenderer}: produces Presentation MathML
 * (mo/mn/mi/mfrac/msup/msqrt/...). Mainly used as data source for tools that
 * cannot consume LaTeX. Falls back to wrapping the raw expression in an
 * {@code <mtext>} element when parsing fails.</p>
 */
public class AstMathMlRenderer implements MathRenderer {

    private final ExpressionParser parser = new ExpressionParser();

    @Override
    public String renderExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return "<math xmlns=\"http://www.w3.org/1998/Math/MathML\"></math>";
        }
        try {
            if (expression.contains("=") && !expression.contains(";") && !expression.contains("\n")) {
                Equation equation = parser.parseEquation(expression);
                return wrap(render(equation.left()) + "<mo>=</mo>" + render(equation.right()));
            }
            if (expression.contains("=") && (expression.contains(";") || expression.contains("\n"))) {
                StringBuilder sb = new StringBuilder();
                for (Equation eq : parser.parse(new InputRequest(InputType.SYSTEM, expression)).equations()) {
                    sb.append("<mrow>").append(render(eq.left())).append("<mo>=</mo>")
                        .append(render(eq.right())).append("</mrow>");
                }
                return wrap("<mfenced open=\"{\" close=\"\" separators=\"\">" + sb + "</mfenced>");
            }
            Expr expr = parser.parseTerm(expression);
            return wrap(render(expr));
        } catch (RuntimeException ex) {
            return wrap("<mtext>" + escape(expression) + "</mtext>");
        }
    }

    @Override
    public String renderStep(TransformationStep step) {
        return renderExpression(step.beforeExpression()) + "\n<mo>&#x2192;</mo>\n"
            + renderExpression(step.afterExpression());
    }

    @Override
    public String renderPath(DiscoveredTransformation path) {
        StringBuilder sb = new StringBuilder();
        sb.append("<mrow>");
        List<TransformationStep> steps = path.steps();
        if (steps.isEmpty()) {
            sb.append(renderExpression(path.originalExpression()))
                .append("<mo>&#x2192;</mo>")
                .append(renderExpression(path.improvedExpression()));
        } else {
            sb.append(renderExpression(steps.get(0).beforeExpression()));
            for (TransformationStep step : steps) {
                sb.append("<mo>&#x2192;</mo>");
                sb.append(renderExpression(step.afterExpression()));
            }
        }
        sb.append("</mrow>");
        return sb.toString();
    }

    private String wrap(String inner) {
        return "<math xmlns=\"http://www.w3.org/1998/Math/MathML\"><mrow>" + inner + "</mrow></math>";
    }

    private String render(Expr expr) {
        if (expr instanceof NumberExpr number) {
            double value = number.value();
            return "<mn>" + (value == (long) value ? Long.toString((long) value) : Double.toString(value)) + "</mn>";
        }
        if (expr instanceof VariableExpr variable) {
            return "<mi>" + escape(variable.name()) + "</mi>";
        }
        if (expr instanceof FunctionExpr fn) {
            return renderFunction(fn);
        }
        if (expr instanceof BinaryExpr binary) {
            return renderBinary(binary);
        }
        return "";
    }

    private String renderBinary(BinaryExpr binary) {
        BinaryOperator op = binary.operator();
        return switch (op) {
            case ADD -> "<mrow>" + render(binary.left()) + "<mo>+</mo>" + render(binary.right()) + "</mrow>";
            case SUB -> "<mrow>" + render(binary.left()) + "<mo>-</mo>" + render(binary.right()) + "</mrow>";
            case MUL -> "<mrow>" + render(binary.left()) + "<mo>&#x22C5;</mo>" + render(binary.right()) + "</mrow>";
            case DIV -> "<mfrac>" + render(binary.left()) + render(binary.right()) + "</mfrac>";
            case POW -> "<msup>" + render(binary.left()) + render(binary.right()) + "</msup>";
        };
    }

    private String renderFunction(FunctionExpr fn) {
        String name = fn.name().toLowerCase();
        if ("sqrt".equals(name) && fn.arguments().size() == 1) {
            return "<msqrt>" + render(fn.arguments().get(0)) + "</msqrt>";
        }
        if ("abs".equals(name) && fn.arguments().size() == 1) {
            return "<mrow><mo>|</mo>" + render(fn.arguments().get(0)) + "<mo>|</mo></mrow>";
        }
        StringBuilder sb = new StringBuilder("<mrow><mi>").append(escape(name)).append("</mi><mo>(</mo>");
        for (int i = 0; i < fn.arguments().size(); i++) {
            if (i > 0) {
                sb.append("<mo>,</mo>");
            }
            sb.append(render(fn.arguments().get(i)));
        }
        return sb.append("<mo>)</mo></mrow>").toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
