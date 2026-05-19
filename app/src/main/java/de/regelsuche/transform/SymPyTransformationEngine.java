package de.regelsuche.transform;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public class SymPyTransformationEngine implements TransformationEngine {
    private final TransformationEngine astRewriteFallback = new AstRewriteTransformationEngine();

    @Override
    public List<Transformation> transform(String expression) {
        Set<Transformation> transformations = new LinkedHashSet<>();
        transformations.addAll(astRewriteFallback.transform(expression));
        List<Transformation> symPyResults = trySymPy(expression);
        transformations.addAll(symPyResults);
        return new ArrayList<>(transformations);
    }

    private List<Transformation> trySymPy(String expression) {
        String escaped = expression.replace("^", "**").replace("\\", "\\\\").replace("'", "\\'");
        String script = "import sympy as sp\\n"
            + "expr = sp.sympify('" + escaped + "')\\n"
            + "results = [str(sp.simplify(expr)), str(sp.expand(expr)), str(sp.factor(expr))]\\n"
            + "results";

        try (Context context = Context.newBuilder("python").allowAllAccess(true).build()) {
            Value result = context.eval("python", script);
            Set<String> unique = new LinkedHashSet<>();
            for (int i = 0; i < result.getArraySize(); i++) {
                unique.add(result.getArrayElement(i).asString());
            }
            List<Transformation> transformations = new ArrayList<>();
            for (String candidate : unique) {
                if (!candidate.isBlank() && !candidate.equals(expression)) {
                    transformations.add(new Transformation("sympy", candidate));
                }
            }
            return transformations;
        } catch (RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

}
