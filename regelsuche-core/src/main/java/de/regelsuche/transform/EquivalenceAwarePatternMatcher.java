package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Matches pattern expressions against ASTs modulo a deliberately bounded set
 * of equivalences.
 *
 * <p>The matcher supports associativity and commutativity for selected binary
 * operators. With algebraic binding inference enabled it additionally solves a
 * bounded monomial fragment: numeric products/quotients, variables and integer
 * powers. This permits globally consistent bindings such as
 * {@code A = 3/2*a} while still rejecting an expression whose remaining terms
 * contradict that binding.</p>
 */
public final class EquivalenceAwarePatternMatcher {
    private static final int MAX_COMMUTATIVE_OPERANDS = 8;
    private static final double EPSILON = 1.0e-9;

    private EquivalenceAwarePatternMatcher() {
    }

    public static boolean match(
        PatternExpr pattern,
        Expr expression,
        Map<String, Expr> bindings,
        RecognitionProfile profile
    ) {
        if (pattern == null || expression == null || bindings == null || profile == null) {
            throw new IllegalArgumentException("pattern, expression, bindings and profile are required");
        }
        return matchInternal(pattern, expression, bindings, profile);
    }

    private static boolean matchInternal(
        PatternExpr pattern,
        Expr expression,
        Map<String, Expr> bindings,
        RecognitionProfile profile
    ) {
        if (pattern instanceof PatternExpr.Placeholder placeholder) {
            Expr bound = bindings.get(placeholder.name());
            if (bound == null) {
                bindings.put(placeholder.name(), expression);
                return true;
            }
            return equivalent(bound, expression, profile);
        }
        if (pattern instanceof PatternExpr.LiteralNumber number) {
            if (expression instanceof NumberExpr numberExpr) {
                return nearlyEqual(numberExpr.value(), number.value());
            }
            return profile.inferAlgebraicBindings()
                && Monomial.from(expression).map(m -> m.isConstant(number.value())).orElse(false);
        }
        if (pattern instanceof PatternExpr.LiteralVariable variable) {
            return expression instanceof VariableExpr variableExpr && variableExpr.name().equals(variable.name());
        }
        if (pattern instanceof PatternExpr.Function function) {
            if (!(expression instanceof FunctionExpr functionExpr)
                || !functionExpr.name().equals(function.name())
                || functionExpr.arguments().size() != function.arguments().size()) {
                return false;
            }
            for (int i = 0; i < function.arguments().size(); i++) {
                if (!matchInternal(function.arguments().get(i), functionExpr.arguments().get(i), bindings, profile)) {
                    return false;
                }
            }
            return true;
        }

        PatternExpr.Operation operation = (PatternExpr.Operation) pattern;
        if (profile.inferAlgebraicBindings() && allPlaceholdersBound(operation, bindings)) {
            Expr instantiated = operation.instantiate(bindings);
            if (equivalent(instantiated, expression, profile)) {
                return true;
            }
        }
        if (profile.inferAlgebraicBindings() && tryInferPowerBinding(operation, expression, bindings, profile)) {
            return true;
        }
        if (!(expression instanceof BinaryExpr binaryExpr) || binaryExpr.operator() != operation.operator()) {
            return false;
        }
        if (!profile.isAssociative(operation.operator())) {
            return matchInternal(operation.left(), binaryExpr.left(), bindings, profile)
                && matchInternal(operation.right(), binaryExpr.right(), bindings, profile);
        }

        List<PatternExpr> patternOperands = new ArrayList<>();
        flattenPattern(operation, operation.operator(), patternOperands);
        List<Expr> expressionOperands = new ArrayList<>();
        flattenExpression(binaryExpr, operation.operator(), expressionOperands);
        if (patternOperands.size() != expressionOperands.size()) {
            return false;
        }
        if (profile.isCommutative(operation.operator())) {
            if (patternOperands.size() > MAX_COMMUTATIVE_OPERANDS) {
                return false;
            }
            patternOperands.sort(Comparator.comparingInt(EquivalenceAwarePatternMatcher::bindingPriority).reversed());
            return matchCommutative(patternOperands, expressionOperands, 0, bindings, profile);
        }
        for (int i = 0; i < patternOperands.size(); i++) {
            if (!matchInternal(patternOperands.get(i), expressionOperands.get(i), bindings, profile)) {
                return false;
            }
        }
        return true;
    }

    private static boolean tryInferPowerBinding(
        PatternExpr.Operation operation,
        Expr expression,
        Map<String, Expr> bindings,
        RecognitionProfile profile
    ) {
        if (operation.operator() != BinaryOperator.POW
            || !(operation.left() instanceof PatternExpr.Placeholder placeholder)
            || !(operation.right() instanceof PatternExpr.LiteralNumber exponentLiteral)) {
            return false;
        }
        int exponent = exactPositiveInteger(exponentLiteral.value());
        if (exponent < 1) {
            return false;
        }
        Expr existing = bindings.get(placeholder.name());
        if (existing != null) {
            return equivalent(operation.instantiate(bindings), expression, profile);
        }
        var monomial = Monomial.from(expression);
        if (monomial.isEmpty()) {
            return false;
        }
        var root = monomial.get().exactRoot(exponent);
        if (root.isEmpty()) {
            return false;
        }
        bindings.put(placeholder.name(), root.get().toExpr());
        return equivalent(operation.instantiate(bindings), expression, profile);
    }

    private static int bindingPriority(PatternExpr pattern) {
        if (pattern instanceof PatternExpr.Operation operation
            && operation.operator() == BinaryOperator.POW
            && operation.left() instanceof PatternExpr.Placeholder
            && operation.right() instanceof PatternExpr.LiteralNumber) {
            return 30;
        }
        if (pattern instanceof PatternExpr.LiteralNumber || pattern instanceof PatternExpr.LiteralVariable) {
            return 20;
        }
        if (pattern instanceof PatternExpr.Placeholder) {
            return 5;
        }
        return 10;
    }

    private static boolean allPlaceholdersBound(PatternExpr pattern, Map<String, Expr> bindings) {
        if (pattern instanceof PatternExpr.Placeholder placeholder) {
            return bindings.containsKey(placeholder.name());
        }
        if (pattern instanceof PatternExpr.Operation operation) {
            return allPlaceholdersBound(operation.left(), bindings)
                && allPlaceholdersBound(operation.right(), bindings);
        }
        if (pattern instanceof PatternExpr.Function function) {
            return function.arguments().stream().allMatch(argument -> allPlaceholdersBound(argument, bindings));
        }
        return true;
    }

    private static boolean equivalent(Expr left, Expr right, RecognitionProfile profile) {
        if (left.equals(right)) {
            return true;
        }
        if (!profile.inferAlgebraicBindings()) {
            return false;
        }
        var leftMonomial = Monomial.from(left);
        var rightMonomial = Monomial.from(right);
        return leftMonomial.isPresent() && rightMonomial.isPresent() && leftMonomial.get().equivalentTo(rightMonomial.get());
    }

    private static boolean matchCommutative(
        List<PatternExpr> patterns,
        List<Expr> expressions,
        int patternIndex,
        Map<String, Expr> bindings,
        RecognitionProfile profile
    ) {
        if (patternIndex == patterns.size()) {
            return expressions.isEmpty();
        }
        PatternExpr pattern = patterns.get(patternIndex);
        for (int i = 0; i < expressions.size(); i++) {
            Map<String, Expr> candidateBindings = new HashMap<>(bindings);
            if (!matchInternal(pattern, expressions.get(i), candidateBindings, profile)) {
                continue;
            }
            List<Expr> remaining = new ArrayList<>(expressions);
            remaining.remove(i);
            if (matchCommutative(patterns, remaining, patternIndex + 1, candidateBindings, profile)) {
                bindings.clear();
                bindings.putAll(candidateBindings);
                return true;
            }
        }
        return false;
    }

    private static void flattenPattern(PatternExpr pattern, BinaryOperator operator, List<PatternExpr> result) {
        if (pattern instanceof PatternExpr.Operation operation && operation.operator() == operator) {
            flattenPattern(operation.left(), operator, result);
            flattenPattern(operation.right(), operator, result);
        } else {
            result.add(pattern);
        }
    }

    private static void flattenExpression(Expr expression, BinaryOperator operator, List<Expr> result) {
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == operator) {
            flattenExpression(binaryExpr.left(), operator, result);
            flattenExpression(binaryExpr.right(), operator, result);
        } else {
            result.add(expression);
        }
    }

    private static int exactPositiveInteger(double value) {
        int integer = (int) Math.rint(value);
        return integer > 0 && nearlyEqual(value, integer) ? integer : -1;
    }

    private static boolean nearlyEqual(double left, double right) {
        return Math.abs(left - right) <= EPSILON * Math.max(1.0, Math.max(Math.abs(left), Math.abs(right)));
    }

    private record Monomial(double coefficient, Map<String, Integer> powers) {
        private Monomial {
            powers = Map.copyOf(new TreeMap<>(powers));
        }

        static java.util.Optional<Monomial> from(Expr expression) {
            if (expression instanceof NumberExpr number) {
                return java.util.Optional.of(new Monomial(number.value(), Map.of()));
            }
            if (expression instanceof VariableExpr variable) {
                return java.util.Optional.of(new Monomial(1.0, Map.of(variable.name(), 1)));
            }
            if (!(expression instanceof BinaryExpr binary)) {
                return java.util.Optional.empty();
            }
            if (binary.operator() == BinaryOperator.MUL || binary.operator() == BinaryOperator.DIV) {
                var left = from(binary.left());
                var right = from(binary.right());
                if (left.isEmpty() || right.isEmpty()) {
                    return java.util.Optional.empty();
                }
                if (binary.operator() == BinaryOperator.DIV && nearlyEqual(right.get().coefficient, 0.0)) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(binary.operator() == BinaryOperator.MUL
                    ? left.get().multiply(right.get())
                    : left.get().divide(right.get()));
            }
            if (binary.operator() == BinaryOperator.POW && binary.right() instanceof NumberExpr exponentExpr) {
                int exponent = exactPositiveInteger(exponentExpr.value());
                var base = from(binary.left());
                if (exponent < 1 || base.isEmpty()) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(base.get().pow(exponent));
            }
            return java.util.Optional.empty();
        }

        Monomial multiply(Monomial other) {
            Map<String, Integer> result = new TreeMap<>(powers);
            other.powers.forEach((name, exponent) -> result.merge(name, exponent, Integer::sum));
            return new Monomial(coefficient * other.coefficient, result);
        }

        Monomial divide(Monomial other) {
            Map<String, Integer> result = new TreeMap<>(powers);
            other.powers.forEach((name, exponent) -> result.merge(name, -exponent, Integer::sum));
            result.entrySet().removeIf(entry -> entry.getValue() == 0);
            return new Monomial(coefficient / other.coefficient, result);
        }

        Monomial pow(int exponent) {
            Map<String, Integer> result = new TreeMap<>();
            powers.forEach((name, power) -> result.put(name, power * exponent));
            return new Monomial(Math.pow(coefficient, exponent), result);
        }

        java.util.Optional<Monomial> exactRoot(int exponent) {
            if (coefficient < 0.0 && exponent % 2 == 0) {
                return java.util.Optional.empty();
            }
            Map<String, Integer> result = new TreeMap<>();
            for (var entry : powers.entrySet()) {
                if (entry.getValue() % exponent != 0) {
                    return java.util.Optional.empty();
                }
                result.put(entry.getKey(), entry.getValue() / exponent);
            }
            double rootCoefficient = coefficient < 0.0
                ? -Math.pow(-coefficient, 1.0 / exponent)
                : Math.pow(coefficient, 1.0 / exponent);
            if (!nearlyEqual(Math.pow(rootCoefficient, exponent), coefficient)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new Monomial(rootCoefficient, result));
        }

        boolean equivalentTo(Monomial other) {
            return nearlyEqual(coefficient, other.coefficient) && powers.equals(other.powers);
        }

        boolean isConstant(double expected) {
            return powers.isEmpty() && nearlyEqual(coefficient, expected);
        }

        Expr toExpr() {
            Expr result = null;
            if (!nearlyEqual(coefficient, 1.0) || powers.isEmpty()) {
                result = new NumberExpr(coefficient);
            }
            for (var entry : powers.entrySet()) {
                Expr factor = new VariableExpr(entry.getKey());
                if (entry.getValue() != 1) {
                    factor = new BinaryExpr(factor, BinaryOperator.POW, new NumberExpr(entry.getValue()));
                }
                result = result == null ? factor : new BinaryExpr(result, BinaryOperator.MUL, factor);
            }
            return result == null ? new NumberExpr(1.0) : result;
        }
    }
}
