package de.regelsuche.validation;

import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public class SymPyOracleValidator implements OracleValidator {
    private final ExpressionParser parser = new ExpressionParser();

    @Override
    public OracleValidation validateEquivalence(String leftExpression, String rightExpression) {
        String left;
        String right;
        try {
            left = escape(toSymPyPowerSyntax(ExpressionFormatter.format(parser.parseTerm(leftExpression))));
            right = escape(toSymPyPowerSyntax(ExpressionFormatter.format(parser.parseTerm(rightExpression))));
        } catch (IllegalArgumentException ex) {
            return OracleValidation.unavailable("oracle input could not be parsed");
        }
        String script = "import sympy as sp\n"
            + "from sympy.parsing.sympy_parser import parse_expr\n"
            + "lhs = parse_expr('" + left + "', evaluate=False)\n"
            + "rhs = parse_expr('" + right + "', evaluate=False)\n"
            + "sp.simplify(lhs - rhs) == 0";
        try (Context context = Context.newBuilder("python").build()) {
            Value value = context.eval("python", script);
            return value.asBoolean()
                ? OracleValidation.agrees("SymPy simplify(lhs - rhs) == 0")
                : OracleValidation.disagrees("SymPy simplify(lhs - rhs) != 0");
        } catch (RuntimeException | LinkageError ignored) {
            return OracleValidation.unavailable("python/sympy runtime unavailable");
        }
    }

    private String escape(String expression) {
        return expression.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String toSymPyPowerSyntax(String expression) {
        return expression.replace("^", "**");
    }
}
