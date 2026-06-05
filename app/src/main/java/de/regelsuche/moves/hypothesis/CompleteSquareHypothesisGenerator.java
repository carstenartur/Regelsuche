package de.regelsuche.moves.hypothesis;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.moves.RewriteMoveKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recognises the completing-the-square pattern {@code A^2 + b*A + c} on the
 * {@link TermSkeleton skeleton} level, where {@code A} may be an arbitrary
 * subtree.
 *
 * <p>For a matching skeleton it emits {@code shift = b/2} and
 * {@code residue = c - (b/2)^2}, plus a {@link HypothesisSource#SKELETON_MATCH}
 * hypothesis naming the atom that was abstracted.</p>
 *
 * <ul>
 *   <li>{@code x^2 + 6*x + 5} (A = x) → shift 3, residue -4</li>
 *   <li>{@code (a+b)^2 + 6*(a+b) + 5} (A = a+b) → shift 3, residue -4</li>
 *   <li>{@code (sin(x)+cos(x))^2 + 2*(sin(x)+cos(x)) + 1} → shift 1, residue 0</li>
 * </ul>
 */
public final class CompleteSquareHypothesisGenerator implements ParameterHypothesisGenerator {

    private static final int MAX_DEGREE = 8;

    @Override
    public String id() {
        return "complete-square";
    }

    @Override
    public List<ParameterHypothesis> propose(ParameterContext context) {
        if (!context.allows(RewriteMoveKind.COMPLETE_SQUARE)) {
            return List.of();
        }
        List<ParameterHypothesis> result = new ArrayList<>();
        Set<String> matchedAtoms = new LinkedHashSet<>();
        for (TermSkeleton skeleton : context.skeletons()) {
            if (!matchedAtoms.add(skeleton.atomCanonical())) {
                continue;
            }
            Quadratic quadratic = analyse(skeleton.skeleton(), skeleton.placeholder());
            if (quadratic == null) {
                continue;
            }
            double shift = quadratic.b() / 2.0;
            double residue = quadratic.c() - shift * shift;
            String atom = skeleton.atomCanonical();
            List<String> evidence = List.of(
                    "atom=" + atom,
                    "skeleton=" + skeleton.skeletonText());
            result.add(new ParameterHypothesis(
                    RewriteMoveKind.COMPLETE_SQUARE,
                    "shift",
                    HypothesisExpressions.formatNumber(shift),
                    HypothesisExpressions.formatNumber(shift),
                    HypothesisSource.COMPLETE_SQUARE,
                    0.9,
                    "complete square on " + atom,
                    evidence));
            result.add(new ParameterHypothesis(
                    RewriteMoveKind.COMPLETE_SQUARE,
                    "residue",
                    HypothesisExpressions.formatNumber(residue),
                    HypothesisExpressions.formatNumber(residue),
                    HypothesisSource.COMPLETE_SQUARE,
                    0.9,
                    "complete square on " + atom,
                    evidence));
            result.add(new ParameterHypothesis(
                    RewriteMoveKind.COMPLETE_SQUARE,
                    "atom",
                    atom,
                    atom,
                    HypothesisSource.SKELETON_MATCH,
                    0.9,
                    "quadratic skeleton " + skeleton.skeletonText(),
                    evidence));
        }
        result.sort(ParameterHypothesis.CANONICAL_ORDER);
        return List.copyOf(result);
    }

    /** Analyses a skeleton as a quadratic in the single placeholder variable. */
    private Quadratic analyse(Expr skeleton, String placeholder) {
        Set<String> variables = new LinkedHashSet<>();
        collectVariables(skeleton, variables);
        if (variables.size() != 1 || !variables.contains(placeholder)) {
            return null;
        }
        Map<Integer, Double> polynomial = toPolynomial(skeleton);
        if (polynomial == null) {
            return null;
        }
        double leading = polynomial.getOrDefault(2, 0.0);
        if (leading == 0.0) {
            return null;
        }
        // Reject anything of degree higher than 2.
        for (Integer degree : polynomial.keySet()) {
            if (degree > 2 && polynomial.get(degree) != 0.0) {
                return null;
            }
        }
        double b = polynomial.getOrDefault(1, 0.0) / leading;
        double c = polynomial.getOrDefault(0, 0.0) / leading;
        return new Quadratic(b, c);
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
                if (degree > MAX_DEGREE) {
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
        if (raw != Math.rint(raw) || raw < 0 || raw > MAX_DEGREE) {
            return null;
        }
        int power = (int) raw;
        Map<Integer, Double> result = new HashMap<>(Map.of(0, 1.0));
        for (int i = 0; i < power; i++) {
            result = multiply(result, base);
            if (result == null) {
                return null;
            }
        }
        return result;
    }

    private record Quadratic(double b, double c) {
    }
}
