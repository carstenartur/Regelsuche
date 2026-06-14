package de.regelsuche.moves.enumerate;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.MoveParameterKind;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Recognises a univariate quadratic {@code x^2 + b*x + c} and emits the
 * completing-the-square parameters {@code shift = b/2} and
 * {@code residue = c - (b/2)^2}.
 *
 * <p>For example {@code x^2 + 6*x + 5} yields {@code shift = 3} and
 * {@code residue = -4} (because {@code (x + 3)^2 - 4}).</p>
 */
public final class CompleteSquareParameterEnumerator implements ParameterEnumerator {

    @Override
    public String id() {
        return "complete-square";
    }

    @Override
    public List<MoveParameter> enumerate(String expression) {
        return MoveExpressions.parse(expression)
                .flatMap(this::fromExpr)
                .orElseGet(List::of);
    }

    @Override
    public List<MoveParameter> enumerate(Expr expr) {
        return fromExpr(expr).orElseGet(List::of);
    }

    private Optional<List<MoveParameter>> fromExpr(Expr root) {
        Set<String> variables = new LinkedHashSet<>();
        collectVariables(root, variables);
        if (variables.size() != 1) {
            return Optional.empty();
        }
        Map<Integer, Double> polynomial = toPolynomial(root);
        if (polynomial == null) {
            return Optional.empty();
        }
        double leading = polynomial.getOrDefault(2, 0.0);
        if (leading == 0.0) {
            return Optional.empty();
        }
        double b = polynomial.getOrDefault(1, 0.0) / leading;
        double c = polynomial.getOrDefault(0, 0.0) / leading;
        double shift = b / 2.0;
        double residue = c - shift * shift;
        return Optional.of(List.of(
                new MoveParameter("shift", MoveParameterKind.GENERATED, format(shift), format(shift), 0, id()),
                new MoveParameter("residue", MoveParameterKind.GENERATED, format(residue), format(residue), 1, id())));
    }

    private void collectVariables(Expr expr, Set<String> out) {
        if (expr instanceof VariableExpr variable) {
            out.add(variable.name());
        } else if (expr instanceof BinaryExpr binary) {
            collectVariables(binary.left(), out);
            collectVariables(binary.right(), out);
        } else if (expr instanceof FunctionExpr function) {
            function.arguments().forEach(argument -> collectVariables(argument, out));
        }
    }

    /** @return the polynomial as degree -> coefficient, or {@code null} when not a univariate polynomial. */
    private Map<Integer, Double> toPolynomial(Expr expr) {
        if (expr instanceof NumberExpr number) {
            return Map.of(0, number.value());
        }
        if (expr instanceof VariableExpr) {
            return Map.of(1, 1.0);
        }
        if (expr instanceof BinaryExpr binary) {
            Map<Integer, Double> left = toPolynomial(binary.left());
            Map<Integer, Double> right = toPolynomial(binary.right());
            if (left == null || right == null) {
                return null;
            }
            return switch (binary.operator()) {
                case ADD -> add(left, right, 1.0);
                case SUB -> add(left, right, -1.0);
                case MUL -> multiply(left, right);
                case DIV -> divide(left, right);
                case POW -> power(left, right);
            };
        }
        return null;
    }

    private Map<Integer, Double> add(Map<Integer, Double> left, Map<Integer, Double> right, double sign) {
        Map<Integer, Double> result = new HashMap<>(left);
        right.forEach((degree, coefficient) -> result.merge(degree, sign * coefficient, Double::sum));
        return result;
    }

    private Map<Integer, Double> multiply(Map<Integer, Double> left, Map<Integer, Double> right) {
        Map<Integer, Double> result = new HashMap<>();
        for (Map.Entry<Integer, Double> leftEntry : left.entrySet()) {
            for (Map.Entry<Integer, Double> rightEntry : right.entrySet()) {
                int degree = leftEntry.getKey() + rightEntry.getKey();
                if (degree > 8) {
                    return null;
                }
                result.merge(degree, leftEntry.getValue() * rightEntry.getValue(), Double::sum);
            }
        }
        return result;
    }

    private Map<Integer, Double> divide(Map<Integer, Double> left, Map<Integer, Double> right) {
        if (right.size() != 1 || !right.containsKey(0)) {
            return null;
        }
        double divisor = right.get(0);
        if (divisor == 0.0) {
            return null;
        }
        Map<Integer, Double> result = new HashMap<>();
        left.forEach((degree, coefficient) -> result.put(degree, coefficient / divisor));
        return result;
    }

    private Map<Integer, Double> power(Map<Integer, Double> base, Map<Integer, Double> exponent) {
        if (exponent.size() != 1 || !exponent.containsKey(0)) {
            return null;
        }
        double raw = exponent.get(0);
        if (raw != Math.rint(raw) || raw < 0 || raw > 8) {
            return null;
        }
        int power = (int) raw;
        Map<Integer, Double> result = Map.of(0, 1.0);
        for (int i = 0; i < power; i++) {
            result = multiply(result, base);
            if (result == null) {
                return null;
            }
        }
        return result;
    }

    private String format(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
