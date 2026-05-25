package de.regelsuche.equivalence;

import de.regelsuche.algebra.QuadraticAnalyzer;
import de.regelsuche.algebra.QuadraticCoefficients;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public class SymPyEquivalenceService implements EquivalenceService {
    private final ExpressionParser parser = new ExpressionParser();

    @Override
    public boolean areEquivalent(String leftExpression, String rightExpression) {
        Boolean symPyResult = trySymPy(leftExpression, rightExpression);
        if (symPyResult != null) {
            return symPyResult;
        }
        Boolean sampledResult = trySampledNumericEquivalence(leftExpression, rightExpression);
        if (sampledResult != null) {
            return sampledResult;
        }
        Optional<QuadraticCoefficients> left = QuadraticAnalyzer.analyze(leftExpression);
        Optional<QuadraticCoefficients> right = QuadraticAnalyzer.analyze(rightExpression);
        return left.isPresent() && right.isPresent() && left.orElseThrow().equals(right.orElseThrow());
    }

    @Override
    public String evidence(String leftExpression, String rightExpression) {
        Boolean symPyResult = trySymPy(leftExpression, rightExpression);
        if (Boolean.TRUE.equals(symPyResult)) {
            return "SymPy simplify(lhs - rhs) == 0";
        }
        Boolean sampledResult = trySampledNumericEquivalence(leftExpression, rightExpression);
        if (Boolean.TRUE.equals(sampledResult)) {
            return "validated by deterministic numeric samples";
        }
        Optional<QuadraticCoefficients> left = QuadraticAnalyzer.analyze(leftExpression);
        Optional<QuadraticCoefficients> right = QuadraticAnalyzer.analyze(rightExpression);
        if (left.isPresent() && right.isPresent() && left.orElseThrow().equals(right.orElseThrow())) {
            return "matching normalized quadratic coefficients";
        }
        return "no equivalence proof found";
    }

    private Boolean trySymPy(String leftExpression, String rightExpression) {
        String left;
        String right;
        try {
            left = escape(toSymPyPowerSyntax(ExpressionFormatter.format(parser.parseTerm(leftExpression))));
            right = escape(toSymPyPowerSyntax(ExpressionFormatter.format(parser.parseTerm(rightExpression))));
        } catch (IllegalArgumentException ex) {
            return null;
        }
        String script = "import sympy as sp\n"
            + "from sympy.parsing.sympy_parser import parse_expr\n"
            + "lhs = parse_expr('" + left + "', evaluate=False)\n"
            + "rhs = parse_expr('" + right + "', evaluate=False)\n"
            + "sp.simplify(lhs - rhs) == 0";
        try (Context context = Context.newBuilder("python").build()) {
            Value value = context.eval("python", script);
            return value.asBoolean();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private String escape(String expression) {
        return expression.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String toSymPyPowerSyntax(String expression) {
        return expression.replace("^", "**");
    }

    private Boolean trySampledNumericEquivalence(String leftExpression, String rightExpression) {
        Expr left;
        Expr right;
        try {
            left = parser.parseTerm(leftExpression);
            right = parser.parseTerm(rightExpression);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        Set<String> variables = new HashSet<>();
        collectVariables(left, variables);
        collectVariables(right, variables);
        for (int seed = 1; seed <= 7; seed++) {
            Map<String, Double> assignment = new HashMap<>();
            int offset = 0;
            for (String variable : variables) {
                assignment.put(variable, (double) (seed + offset + 1));
                offset++;
            }
            double leftValue = evaluate(left, assignment);
            double rightValue = evaluate(right, assignment);
            if (!Double.isFinite(leftValue) || !Double.isFinite(rightValue) || Math.abs(leftValue - rightValue) > 1e-7) {
                return false;
            }
        }
        return true;
    }

    private void collectVariables(Expr expression, Set<String> variables) {
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

    private double evaluate(Expr expression, Map<String, Double> variables) {
        if (expression instanceof NumberExpr numberExpr) {
            return numberExpr.value();
        }
        if (expression instanceof VariableExpr variableExpr) {
            return variables.getOrDefault(variableExpr.name(), 0.0);
        }
        if (expression instanceof FunctionExpr functionExpr) {
            return evaluateFunction(functionExpr, variables);
        }
        BinaryExpr binaryExpr = (BinaryExpr) expression;
        double left = evaluate(binaryExpr.left(), variables);
        double right = evaluate(binaryExpr.right(), variables);
        BinaryOperator operator = binaryExpr.operator();
        return switch (operator) {
            case ADD -> left + right;
            case SUB -> left - right;
            case MUL -> left * right;
            case DIV -> Math.abs(right) < 1e-12 ? Double.NaN : left / right;
            case POW -> Math.pow(left, right);
        };
    }

    private double evaluateFunction(FunctionExpr functionExpr, Map<String, Double> variables) {
        if (functionExpr.arguments().size() != 1) {
            return Double.NaN;
        }
        double argument = evaluate(functionExpr.arguments().get(0), variables);
        if (!Double.isFinite(argument)) {
            return Double.NaN;
        }
        return switch (functionExpr.name()) {
            case "sin" -> Math.sin(argument);
            case "cos" -> Math.cos(argument);
            case "tan" -> Math.tan(argument);
            case "log" -> argument <= 0 ? Double.NaN : Math.log10(argument);
            case "ln" -> argument <= 0 ? Double.NaN : Math.log(argument);
            case "sqrt" -> argument < 0 ? Double.NaN : Math.sqrt(argument);
            case "exp" -> Math.exp(argument);
            case "abs" -> Math.abs(argument);
            default -> Double.NaN;
        };
    }
}
