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
import de.regelsuche.parse.ParsedInput;
import java.util.List;

/**
 * AST-based LaTeX renderer.
 *
 * <p>Parses an expression with {@link ExpressionParser} and recursively
 * pretty-prints the resulting AST as LaTeX. Supports:
 * <ul>
 *   <li>fractions as {@code \frac{·}{·}}</li>
 *   <li>powers as {@code {·}^{·}}</li>
 *   <li>roots as {@code \sqrt{·}}</li>
 *   <li>trigonometric and logarithmic functions ({@code \sin}, {@code \cos},
 *       {@code \tan}, {@code \log}, {@code \ln}, {@code \exp})</li>
 *   <li>equations and equation systems (split on {@code ;} or newline)</li>
 *   <li>correct parenthesisation by operator precedence</li>
 * </ul>
 *
 * <p>When parsing fails, this renderer falls back to escaping the raw string
 * (replacing {@code *} with {@code \cdot}) so callers always get usable
 * LaTeX. This replaces the original naive {@code *}-substitution path.</p>
 */
public class AstLatexRenderer implements MathRenderer {

    private final ExpressionParser parser = new ExpressionParser();

    @Override
    public String renderExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        // Try equation/system first when there's an '='.
        if (expression.contains("=")) {
            try {
                if (expression.contains(";") || expression.contains("\n")) {
                    ParsedInput parsed = parser.parse(new InputRequest(InputType.SYSTEM, expression));
                    if (!parsed.equations().isEmpty()) {
                        return renderSystem(parsed.equations());
                    }
                }
                Equation equation = parser.parseEquation(expression);
                return renderEquation(equation);
            } catch (RuntimeException ignored) {
                // fall through to raw fallback
            }
        }
        try {
            Expr expr = parser.parseTerm(expression);
            return render(expr, /* parentPrecedence */ 0);
        } catch (RuntimeException ex) {
            // Fallback: textual replacement for unparseable input
            return expression.replace("*", " \\cdot ");
        }
    }

    @Override
    public String renderStep(TransformationStep step) {
        return renderExpression(step.beforeExpression()) + " &\\rightarrow " + renderExpression(step.afterExpression());
    }

    @Override
    public String renderPath(DiscoveredTransformation path) {
        StringBuilder body = new StringBuilder();
        body.append("\\begin{align*}\n");
        List<TransformationStep> steps = path.steps();
        if (steps.isEmpty()) {
            body.append(renderExpression(path.originalExpression()))
                .append(" &\\rightarrow ")
                .append(renderExpression(path.improvedExpression()))
                .append(" \\tag{0}\\\\\n");
        } else {
            body.append(renderExpression(steps.get(0).beforeExpression()))
                .append(" &\\rightarrow ")
                .append(renderExpression(steps.get(0).afterExpression()))
                .append(" && \\text{(")
                .append(escapeText(steps.get(0).ruleId()))
                .append(")} \\tag{1}\\\\\n");
            for (int i = 1; i < steps.size(); i++) {
                body.append("        &\\rightarrow ")
                    .append(renderExpression(steps.get(i).afterExpression()))
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

    /** Render an arbitrary {@link Expr} with parent precedence context. */
    public String render(Expr expr, int parentPrecedence) {
        if (expr instanceof NumberExpr number) {
            return formatNumber(number.value());
        }
        if (expr instanceof VariableExpr variable) {
            return variable.name();
        }
        if (expr instanceof FunctionExpr fn) {
            return renderFunction(fn);
        }
        if (expr instanceof BinaryExpr binary) {
            return renderBinary(binary, parentPrecedence);
        }
        return "";
    }

    private String renderBinary(BinaryExpr binary, int parentPrecedence) {
        BinaryOperator op = binary.operator();
        String result = switch (op) {
            case ADD -> render(binary.left(), op.precedence()) + " + " + render(binary.right(), op.precedence());
            case SUB -> render(binary.left(), op.precedence()) + " - " + render(binary.right(), op.precedence() + 1);
            case MUL -> render(binary.left(), op.precedence()) + " \\cdot " + render(binary.right(), op.precedence());
            case DIV -> "\\frac{" + render(binary.left(), 0) + "}{" + render(binary.right(), 0) + "}";
            case POW -> {
                String base = render(binary.left(), op.precedence() + 1);
                String exponent = render(binary.right(), 0);
                yield "{" + base + "}^{" + exponent + "}";
            }
        };
        // Division renders as \frac{} which is self-bracketing; no extra parens.
        if (op == BinaryOperator.DIV) {
            return result;
        }
        if (op.precedence() < parentPrecedence) {
            return "\\left(" + result + "\\right)";
        }
        return result;
    }

    private String renderFunction(FunctionExpr fn) {
        String name = fn.name().toLowerCase();
        String latexName = switch (name) {
            case "sin", "cos", "tan", "log", "ln", "exp" -> "\\" + name;
            case "sqrt" -> "\\sqrt";
            case "abs" -> "\\left|";
            default -> "\\operatorname{" + name + "}";
        };
        if ("sqrt".equals(name) && fn.arguments().size() == 1) {
            return "\\sqrt{" + render(fn.arguments().get(0), 0) + "}";
        }
        if ("abs".equals(name) && fn.arguments().size() == 1) {
            return "\\left|" + render(fn.arguments().get(0), 0) + "\\right|";
        }
        StringBuilder sb = new StringBuilder(latexName).append("\\left(");
        for (int i = 0; i < fn.arguments().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(render(fn.arguments().get(i), 0));
        }
        return sb.append("\\right)").toString();
    }

    private String renderEquation(Equation equation) {
        return render(equation.left(), 0) + " = " + render(equation.right(), 0);
    }

    private String renderSystem(List<Equation> equations) {
        StringBuilder sb = new StringBuilder("\\begin{cases}\n");
        for (int i = 0; i < equations.size(); i++) {
            sb.append(renderEquation(equations.get(i)));
            if (i + 1 < equations.size()) {
                sb.append(" \\\\");
            }
            sb.append('\n');
        }
        return sb.append("\\end{cases}").toString();
    }

    private static String formatNumber(double value) {
        if (value == (long) value) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static String escapeText(String value) {
        return value.replace("_", "\\_");
    }
}
