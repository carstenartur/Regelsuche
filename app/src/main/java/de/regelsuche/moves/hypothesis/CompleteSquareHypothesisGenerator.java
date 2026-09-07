package de.regelsuche.moves.hypothesis;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.moves.RewriteMoveKind;
import java.util.ArrayList;
import de.regelsuche.scalar.ExactRational;
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
            ExactRational shift = quadratic.b().divide(ExactRational.integer(2));
            ExactRational residue = quadratic.c().subtract(shift.multiply(shift));
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
        Map<Integer, ExactRational> polynomial = toPolynomial(skeleton);
        if (polynomial == null) {
            return null;
        }
        ExactRational leading = polynomial.getOrDefault(2, ExactRational.ZERO);
        if (leading.isZero()) {
            return null;
        }
        // Reject anything of degree higher than 2.
        for (Integer degree : polynomial.keySet()) {
            if (degree > 2 && !polynomial.get(degree).isZero()) {
                return null;
            }
        }
        ExactRational b = polynomial.getOrDefault(1, ExactRational.ZERO).divide(leading);
        ExactRational c = polynomial.getOrDefault(0, ExactRational.ZERO).divide(leading);
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
                if (degree > MAX_DEGREE) {
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
        if (!raw.isInteger() || raw.signum() < 0 || raw.numerator().compareTo(java.math.BigInteger.valueOf(MAX_DEGREE)) > 0) {
            return null;
        }
        int power = raw.intValueExact();
        Map<Integer, ExactRational> result = new HashMap<>(Map.of(0, ExactRational.ONE));
        for (int i = 0; i < power; i++) {
            result = multiply(result, base);
            if (result == null) {
                return null;
            }
        }
        return result;
    }

    private record Quadratic(ExactRational b, ExactRational c) {
    }
}
