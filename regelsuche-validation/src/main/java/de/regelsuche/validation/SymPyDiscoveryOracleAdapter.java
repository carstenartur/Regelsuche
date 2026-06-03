package de.regelsuche.validation;

import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/** Optional SymPy-backed discovery oracle adapter with explicit tri-state status. */
public final class SymPyDiscoveryOracleAdapter {
    private final ExpressionParser parser = new ExpressionParser();

    public OracleResult equivalence(String leftExpression, String rightExpression) {
        String left = parseForSymPy(leftExpression);
        String right = parseForSymPy(rightExpression);
        if (left == null || right == null) {
            return OracleResult.unavailable("oracle input could not be parsed");
        }
        String script = "import sympy as sp\n"
            + "from sympy.parsing.sympy_parser import parse_expr\n"
            + "lhs = parse_expr('" + escape(left) + "', evaluate=False)\n"
            + "rhs = parse_expr('" + escape(right) + "', evaluate=False)\n"
            + "sp.simplify(lhs-rhs) == 0";
        return evaluate(script, "SymPy simplify(lhs-rhs)");
    }

    public OracleResult factorCandidate(String expression, String candidateExpression) {
        String input = parseForSymPy(expression);
        String candidate = parseForSymPy(candidateExpression);
        if (input == null || candidate == null) {
            return OracleResult.unavailable("factor candidate input could not be parsed");
        }
        String script = "import sympy as sp\n"
            + "from sympy.parsing.sympy_parser import parse_expr\n"
            + "expr = parse_expr('" + escape(input) + "', evaluate=False)\n"
            + "candidate = parse_expr('" + escape(candidate) + "', evaluate=False)\n"
            + "sp.simplify(sp.expand(expr) - sp.expand(candidate)) == 0";
        return evaluate(script, "SymPy expand equivalence");
    }

    public OracleResult groebnerEquivalence(List<String> generators, String polynomialExpression) {
        if (generators == null || generators.isEmpty()) {
            return OracleResult.unavailable("no generators provided");
        }
        String polynomial = parseForSymPy(polynomialExpression);
        if (polynomial == null) {
            return OracleResult.unavailable("polynomial input could not be parsed");
        }
        java.util.LinkedHashSet<String> symbols = new java.util.LinkedHashSet<>();
        StringBuilder generatorList = new StringBuilder();
        for (int index = 0; index < generators.size(); index++) {
            String parsed = parseForSymPy(generators.get(index));
            if (parsed == null) {
                return OracleResult.unavailable("generator input could not be parsed");
            }
            if (index > 0) {
                generatorList.append(", ");
            }
            generatorList.append("parse_expr('").append(escape(parsed)).append("', evaluate=False)");
            symbols.addAll(variablesOf(generators.get(index)));
        }
        symbols.addAll(variablesOf(polynomialExpression));
        if (symbols.isEmpty()) {
            return OracleResult.unavailable("no symbols for groebner basis");
        }
        String symbolList = symbols.stream().map(this::escape).collect(java.util.stream.Collectors.joining(" "));
        String script = "import sympy as sp\n"
            + "from sympy.parsing.sympy_parser import parse_expr\n"
            + "gens = sp.symbols('" + symbolList + "')\n"
            + "G = sp.groebner([" + generatorList + "], *gens)\n"
            + "poly = parse_expr('" + escape(polynomial) + "', evaluate=False)\n"
            + "G.reduce(poly)[1] == 0";
        return evaluate(script, "SymPy groebner remainder");
    }

    private OracleResult evaluate(String script, String evidenceLabel) {
        try (Context context = Context.newBuilder("python").build()) {
            Value value = context.eval("python", script);
            boolean agree = value.asBoolean();
            return agree
                ? OracleResult.agree(evidenceLabel + " == 0")
                : OracleResult.disagree(evidenceLabel + " != 0");
        } catch (RuntimeException | LinkageError ignored) {
            return OracleResult.unavailable("python/sympy runtime unavailable");
        }
    }

    private String parseForSymPy(String expression) {
        try {
            return ExpressionFormatter.format(parser.parseTerm(expression)).replace("^", "**");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String escape(String expression) {
        return expression.replace("\\", "\\\\").replace("'", "\\'");
    }

    private List<String> variablesOf(String expression) {
        try {
            java.util.LinkedHashSet<String> variables = new java.util.LinkedHashSet<>();
            collectVariables(parser.parseTerm(expression), variables);
            return List.copyOf(variables);
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
    }

    private void collectVariables(Expr expression, java.util.Set<String> variables) {
        if (expression instanceof VariableExpr variableExpr) {
            variables.add(variableExpr.name());
        } else if (expression instanceof BinaryExpr binaryExpr) {
            collectVariables(binaryExpr.left(), variables);
            collectVariables(binaryExpr.right(), variables);
        } else if (expression instanceof FunctionExpr functionExpr) {
            for (Expr argument : functionExpr.arguments()) {
                collectVariables(argument, variables);
            }
        }
    }

    public enum Status {
        AGREE,
        DISAGREE,
        UNAVAILABLE
    }

    public record OracleResult(Status status, String evidence) {
        public OracleResult {
            status = status == null ? Status.UNAVAILABLE : status;
            evidence = evidence == null ? "" : evidence;
        }

        public static OracleResult agree(String evidence) {
            return new OracleResult(Status.AGREE, evidence);
        }

        public static OracleResult disagree(String evidence) {
            return new OracleResult(Status.DISAGREE, evidence);
        }

        public static OracleResult unavailable(String evidence) {
            return new OracleResult(Status.UNAVAILABLE, evidence);
        }
    }
}
