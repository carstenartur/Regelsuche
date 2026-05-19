package de.regelsuche.equivalence;

import de.regelsuche.algebra.QuadraticAnalyzer;
import de.regelsuche.algebra.QuadraticCoefficients;
import java.util.Optional;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public class SymPyEquivalenceService implements EquivalenceService {
    @Override
    public boolean areEquivalent(String leftExpression, String rightExpression) {
        Boolean symPyResult = trySymPy(leftExpression, rightExpression);
        if (symPyResult != null) {
            return symPyResult;
        }
        Optional<QuadraticCoefficients> left = QuadraticAnalyzer.analyze(leftExpression);
        Optional<QuadraticCoefficients> right = QuadraticAnalyzer.analyze(rightExpression);
        return left.isPresent() && right.isPresent() && left.orElseThrow().equals(right.orElseThrow());
    }

    @Override
    public String evidence(String leftExpression, String rightExpression) {
        return areEquivalent(leftExpression, rightExpression)
            ? "simplify(lhs - rhs) == 0 or matching normalized quadratic coefficients"
            : "no equivalence proof found";
    }

    private Boolean trySymPy(String leftExpression, String rightExpression) {
        String left = escape(leftExpression);
        String right = escape(rightExpression);
        String script = "import sympy as sp\n"
            + "lhs = sp.sympify('" + left + "')\n"
            + "rhs = sp.sympify('" + right + "')\n"
            + "sp.simplify(lhs - rhs) == 0";
        try (Context context = Context.newBuilder("python").allowAllAccess(true).build()) {
            Value value = context.eval("python", script);
            return value.asBoolean();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private String escape(String expression) {
        return expression.replace("\\", "\\\\").replace("'", "\\'");
    }
}
