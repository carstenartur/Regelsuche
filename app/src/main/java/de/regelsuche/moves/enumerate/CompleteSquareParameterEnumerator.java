package de.regelsuche.moves.enumerate;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.MoveParameterKind;
import de.regelsuche.scalar.ExactRational;
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
        Map<Integer, ExactRational> polynomial = toPolynomial(root);
        if (polynomial == null) {
            return Optional.empty();
        }
        ExactRational leading = polynomial.getOrDefault(2, ExactRational.ZERO);
        if (leading.isZero()) {
            return Optional.empty();
        }
        ExactRational b = polynomial.getOrDefault(1, ExactRational.ZERO).divide(leading);
        ExactRational c = polynomial.getOrDefault(0, ExactRational.ZERO).divide(leading);
        ExactRational shift = b.divide(ExactRational.integer(2));
        ExactRational residue = c.subtract(shift.multiply(shift));
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
    private Map<Integer, ExactRational> toPolynomial(Expr expr) {
        if (expr instanceof NumberExpr number) {
            return Map.of(0, number.value());
        }
        if (expr instanceof VariableExpr) {
            return Map.of(1, ExactRational.ONE);
        }
        if (expr instanceof BinaryExpr binary) {
            Map<Integer, ExactRational> left = toPolynomial(binary.left());
            Map<Integer, ExactRational> right = toPolynomial(binary.right());
            if (left == null || right == null) {
                return null;
            }
            return switch (binary.operator()) {
                case ADD -> add(left, right, ExactRational.ONE);
                case SUB -> add(left, right, ExactRational.NEGATIVE_ONE);
                case MUL -> multiply(left, right);
                case DIV -> divide(left, right);
                case POW -> power(left, right);
            };
        }
        return null;
    }

    private Map<Integer, ExactRational> add(Map<Integer, ExactRational> left, Map<Integer, ExactRational> right, ExactRational sign) {
        Map<Integer, ExactRational> result = new HashMap<>(left);
        right.forEach((degree, coefficient) -> result.merge(degree, sign.multiply(coefficient), ExactRational::add));
        return result;
    }

    private Map<Integer, ExactRational> multiply(Map<Integer, ExactRational> left, Map<Integer, ExactRational> right) {
        Map<Integer, ExactRational> result = new HashMap<>();
        for (Map.Entry<Integer, ExactRational> leftEntry : left.entrySet()) {
            for (Map.Entry<Integer, ExactRational> rightEntry : right.entrySet()) {
                int degree = leftEntry.getKey() + rightEntry.getKey();
                if (degree > 8) {
                    return null;
                }
                result.merge(degree, leftEntry.getValue().multiply(rightEntry.getValue()), ExactRational::add);
            }
        }
        return result;
    }

    private Map<Integer, ExactRational> divide(Map<Integer, ExactRational> left, Map<Integer, ExactRational> right) {
        if (right.size() != 1 || !right.containsKey(0)) {
            return null;
        }
        ExactRational divisor = right.get(0);
        if (divisor.isZero()) {
            return null;
        }
        Map<Integer, ExactRational> result = new HashMap<>();
        left.forEach((degree, coefficient) -> result.put(degree, coefficient.divide(divisor)));
        return result;
    }

    private Map<Integer, ExactRational> power(Map<Integer, ExactRational> base, Map<Integer, ExactRational> exponent) {
        if (exponent.size() != 1 || !exponent.containsKey(0)) {
            return null;
        }
        ExactRational raw = exponent.get(0);
        if (!raw.isInteger() || raw.signum() < 0 || raw.numerator().compareTo(java.math.BigInteger.valueOf(8)) > 0) {
            return null;
        }
        int power = raw.intValueExact();
        Map<Integer, ExactRational> result = Map.of(0, ExactRational.ONE);
        for (int i = 0; i < power; i++) {
            result = multiply(result, base);
            if (result == null) {
                return null;
            }
        }
        return result;
    }

    private String format(ExactRational value) {
        return de.regelsuche.parse.ExpressionFormatter.format(new NumberExpr(value));
    }
}
