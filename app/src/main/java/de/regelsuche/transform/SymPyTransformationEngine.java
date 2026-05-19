package de.regelsuche.transform;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public class SymPyTransformationEngine implements TransformationEngine {
    private final TransformationEngine astRewriteFallback = new AstRewriteTransformationEngine();
    private final ExpressionParser parser = new ExpressionParser();

    @Override
    public List<Transformation> transform(String expression) {
        Set<Transformation> transformations = new LinkedHashSet<>();
        transformations.addAll(astRewriteFallback.transform(expression));
        List<Transformation> symPyResults = trySymPy(expression);
        transformations.addAll(symPyResults);
        return new ArrayList<>(transformations);
    }

    private List<Transformation> trySymPy(String expression) {
        String normalizedInput;
        try {
            normalizedInput = ExpressionFormatter.format(parser.parseTerm(expression));
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
        String escaped = toSymPyPowerSyntax(normalizedInput).replace("\\", "\\\\").replace("'", "\\'");
        String script = "import sympy as sp\\n"
            + "from sympy.parsing.sympy_parser import parse_expr\\n"
            + "expr = parse_expr('" + escaped + "', evaluate=False)\\n"
            + "results = [str(sp.simplify(expr)), str(sp.expand(expr)), str(sp.factor(expr))]\\n"
            + "results";

        try (Context context = Context.newBuilder("python").build()) {
            Value result = context.eval("python", script);
            Set<String> unique = new LinkedHashSet<>();
            for (int i = 0; i < result.getArraySize(); i++) {
                unique.add(toProjectSyntax(result.getArrayElement(i).asString()));
            }
            List<Transformation> transformations = new ArrayList<>();
            for (String candidate : unique) {
                if (!candidate.isBlank() && !candidate.equals(normalizedInput)) {
                    transformations.add(new Transformation("sympy", candidate));
                }
            }
            return transformations;
        } catch (RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

    private String toProjectSyntax(String expression) {
        String candidate = expression.replace("**", "^");
        try {
            Expr parsed = parser.parseTerm(candidate);
            return ExpressionFormatter.format(parsed);
        } catch (IllegalArgumentException ex) {
            return candidate;
        }
    }

    private String toSymPyPowerSyntax(String expression) {
        return expression.replace("^", "**");
    }
}
