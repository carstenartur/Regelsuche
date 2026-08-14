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
 * Matches expression patterns modulo a deliberately bounded set of
 * equivalences.
 *
 * <p>Associative/commutative matching and algebraic binding inference are
 * explicitly bounded. The detailed API distinguishes a proven non-match from
 * an inconclusive limit outcome. The legacy boolean API remains a compatibility
 * projection and therefore returns {@code false} for inconclusive attempts.</p>
 */
public final class EquivalenceAwarePatternMatcher {
    public static final int DEFAULT_MAX_COMMUTATIVE_OPERANDS = 8;
    public static final int DEFAULT_MAX_BACKTRACKING_BRANCHES = 10_000;

    private static final double EPSILON = 1.0e-9;

    private EquivalenceAwarePatternMatcher() {
    }

    public static boolean match(
        PatternExpr pattern,
        Expr expression,
        Map<String, Expr> bindings,
        RecognitionProfile profile
    ) {
        MatchAttempt attempt = matchDetailed(
            pattern,
            expression,
            bindings,
            profile,
            DEFAULT_MAX_BACKTRACKING_BRANCHES
        );
        if (!attempt.matched()) {
            return false;
        }
        bindings.clear();
        bindings.putAll(attempt.bindings());
        return true;
    }

    public static MatchAttempt matchDetailed(
        PatternExpr pattern,
        Expr expression,
        Map<String, Expr> bindings,
        RecognitionProfile profile
    ) {
        return matchDetailed(
            pattern,
            expression,
            bindings,
            profile,
            DEFAULT_MAX_BACKTRACKING_BRANCHES
        );
    }

    public static MatchAttempt matchDetailed(
        PatternExpr pattern,
        Expr expression,
        Map<String, Expr> bindings,
        RecognitionProfile profile,
        int maxBacktrackingBranches
    ) {
        if (pattern == null || expression == null || bindings == null
                || profile == null) {
            throw new IllegalArgumentException(
                "pattern, expression, bindings and profile are required");
        }
        if (maxBacktrackingBranches < 1) {
            throw new IllegalArgumentException(
                "maxBacktrackingBranches must be positive");
        }
        Map<String, Expr> original = Map.copyOf(bindings);
        Map<String, Expr> working = new HashMap<>(bindings);
        MatchBudget budget = new MatchBudget(maxBacktrackingBranches);
        try {
            boolean matched = matchInternal(
                pattern,
                expression,
                working,
                profile,
                budget
            );
            return new MatchAttempt(
                matched ? AttemptStatus.MATCHED : AttemptStatus.NOT_MATCHED,
                matched ? Map.copyOf(working) : original,
                budget.usedBranches(),
                ""
            );
        } catch (MatchLimitExceeded limit) {
            return new MatchAttempt(
                AttemptStatus.INCONCLUSIVE,
                original,
                budget.usedBranches(),
                limit.code
            );
        }
    }

    private static boolean matchInternal(
        PatternExpr pattern,
        Expr expression,
        Map<String, Expr> bindings,
        RecognitionProfile profile,
        MatchBudget budget
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
                && Monomial.from(expression)
                    .map(monomial -> monomial.isConstant(number.value()))
                    .orElse(false);
        }
        if (pattern instanceof PatternExpr.LiteralVariable variable) {
            return expression instanceof VariableExpr variableExpr
                && variableExpr.name().equals(variable.name());
        }
        if (pattern instanceof PatternExpr.Function function) {
            if (!(expression instanceof FunctionExpr functionExpr)
                    || !functionExpr.name().equals(function.name())
                    || functionExpr.arguments().size()
                        != function.arguments().size()) {
                return false;
            }
            for (int index = 0; index < function.arguments().size(); index++) {
                if (!matchInternal(
                        function.arguments().get(index),
                        functionExpr.arguments().get(index),
                        bindings,
                        profile,
                        budget)) {
                    return false;
                }
            }
            return true;
        }

        PatternExpr.Operation operation = (PatternExpr.Operation) pattern;
        if (profile.inferAlgebraicBindings()
                && allPlaceholdersBound(operation, bindings)) {
            Expr instantiated = operation.instantiate(bindings);
            if (equivalent(instantiated, expression, profile)) {
                return true;
            }
        }
        if (profile.inferAlgebraicBindings()
                && tryInferPowerBinding(
                    operation,
                    expression,
                    bindings,
                    profile)) {
            return true;
        }
        if (!(expression instanceof BinaryExpr binaryExpr)
                || binaryExpr.operator() != operation.operator()) {
            return false;
        }
        if (!profile.isAssociative(operation.operator())) {
            return matchInternal(
                    operation.left(),
                    binaryExpr.left(),
                    bindings,
                    profile,
                    budget)
                && matchInternal(
                    operation.right(),
                    binaryExpr.right(),
                    bindings,
                    profile,
                    budget);
        }

        List<PatternExpr> patternOperands = new ArrayList<>();
        flattenPattern(operation, operation.operator(), patternOperands);
        List<Expr> expressionOperands = new ArrayList<>();
        flattenExpression(binaryExpr, operation.operator(), expressionOperands);
        if (patternOperands.size() != expressionOperands.size()) {
            return false;
        }
        if (profile.isCommutative(operation.operator())) {
            if (patternOperands.size()
                    > DEFAULT_MAX_COMMUTATIVE_OPERANDS) {
                throw new MatchLimitExceeded(
                    "COMMUTATIVE_OPERAND_LIMIT");
            }
            patternOperands.sort(Comparator
                .comparingInt(
                    EquivalenceAwarePatternMatcher::bindingPriority)
                .reversed());
            return matchCommutative(
                patternOperands,
                expressionOperands,
                0,
                bindings,
                profile,
                budget
            );
        }
        for (int index = 0; index < patternOperands.size(); index++) {
            if (!matchInternal(
                    patternOperands.get(index),
                    expressionOperands.get(index),
                    bindings,
                    profile,
                    budget)) {
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
                || !(operation.left()
                    instanceof PatternExpr.Placeholder placeholder)
                || !(operation.right()
                    instanceof PatternExpr.LiteralNumber exponentLiteral)) {
            return false;
        }
        int exponent = exactPositiveInteger(exponentLiteral.value());
        if (exponent < 1) {
            return false;
        }
        Expr existing = bindings.get(placeholder.name());
        if (existing != null) {
            return equivalent(
                operation.instantiate(bindings),
                expression,
                profile
            );
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
        return equivalent(
            operation.instantiate(bindings),
            expression,
            profile
        );
    }

    private static int bindingPriority(PatternExpr pattern) {
        if (pattern instanceof PatternExpr.Operation operation
                && operation.operator() == BinaryOperator.POW
                && operation.left() instanceof PatternExpr.Placeholder
                && operation.right()
                    instanceof PatternExpr.LiteralNumber) {
            return 30;
        }
        if (pattern instanceof PatternExpr.LiteralNumber
                || pattern instanceof PatternExpr.LiteralVariable) {
            return 20;
        }
        if (pattern instanceof PatternExpr.Placeholder) {
            return 5;
        }
        return 10;
    }

    private static boolean allPlaceholdersBound(
        PatternExpr pattern,
        Map<String, Expr> bindings
    ) {
        if (pattern instanceof PatternExpr.Placeholder placeholder) {
            return bindings.containsKey(placeholder.name());
        }
        if (pattern instanceof PatternExpr.Operation operation) {
            return allPlaceholdersBound(operation.left(), bindings)
                && allPlaceholdersBound(operation.right(), bindings);
        }
        if (pattern instanceof PatternExpr.Function function) {
            return function.arguments().stream().allMatch(
                argument -> allPlaceholdersBound(argument, bindings));
        }
        return true;
    }

    private static boolean equivalent(
        Expr left,
        Expr right,
        RecognitionProfile profile
    ) {
        if (left.equals(right)) {
            return true;
        }
        if (!profile.inferAlgebraicBindings()) {
            return false;
        }
        var leftMonomial = Monomial.from(left);
        var rightMonomial = Monomial.from(right);
        return leftMonomial.isPresent()
            && rightMonomial.isPresent()
            && leftMonomial.get().equivalentTo(rightMonomial.get());
    }

    private static boolean matchCommutative(
        List<PatternExpr> patterns,
        List<Expr> expressions,
        int patternIndex,
        Map<String, Expr> bindings,
        RecognitionProfile profile,
        MatchBudget budget
    ) {
        if (patternIndex == patterns.size()) {
            return expressions.isEmpty();
        }
        PatternExpr pattern = patterns.get(patternIndex);
        for (int index = 0; index < expressions.size(); index++) {
            Expr candidate = expressions.get(index);
            if (!couldStructurallyMatch(pattern, candidate, profile)) {
                continue;
            }
            budget.consumeBranch();
            Map<String, Expr> candidateBindings = new HashMap<>(bindings);
            if (!matchInternal(
                    pattern,
                    candidate,
                    candidateBindings,
                    profile,
                    budget)) {
                continue;
            }
            List<Expr> remaining = new ArrayList<>(expressions);
            remaining.remove(index);
            if (matchCommutative(
                    patterns,
                    remaining,
                    patternIndex + 1,
                    candidateBindings,
                    profile,
                    budget)) {
                bindings.clear();
                bindings.putAll(candidateBindings);
                return true;
            }
        }
        return false;
    }

    private static boolean couldStructurallyMatch(
        PatternExpr pattern,
        Expr expression,
        RecognitionProfile profile
    ) {
        if (pattern instanceof PatternExpr.Placeholder) {
            return true;
        }
        if (pattern instanceof PatternExpr.LiteralNumber) {
            return expression instanceof NumberExpr
                || profile.inferAlgebraicBindings()
                    && Monomial.from(expression).isPresent();
        }
        if (pattern instanceof PatternExpr.LiteralVariable) {
            return expression instanceof VariableExpr;
        }
        if (pattern instanceof PatternExpr.Function function) {
            return expression instanceof FunctionExpr candidate
                && function.name().equals(candidate.name())
                && function.arguments().size()
                    == candidate.arguments().size();
        }
        PatternExpr.Operation operation = (PatternExpr.Operation) pattern;
        if (profile.inferAlgebraicBindings()
                && operation.operator() == BinaryOperator.POW) {
            return Monomial.from(expression).isPresent()
                || expression instanceof BinaryExpr binary
                    && binary.operator() == BinaryOperator.POW;
        }
        return expression instanceof BinaryExpr binary
            && binary.operator() == operation.operator();
    }

    private static void flattenPattern(
        PatternExpr pattern,
        BinaryOperator operator,
        List<PatternExpr> result
    ) {
        if (pattern instanceof PatternExpr.Operation operation
                && operation.operator() == operator) {
            flattenPattern(operation.left(), operator, result);
            flattenPattern(operation.right(), operator, result);
        } else {
            result.add(pattern);
        }
    }

    private static void flattenExpression(
        Expr expression,
        BinaryOperator operator,
        List<Expr> result
    ) {
        if (expression instanceof BinaryExpr binaryExpr
                && binaryExpr.operator() == operator) {
            flattenExpression(binaryExpr.left(), operator, result);
            flattenExpression(binaryExpr.right(), operator, result);
        } else {
            result.add(expression);
        }
    }

    private static int exactPositiveInteger(double value) {
        int integer = (int) Math.rint(value);
        return integer > 0 && nearlyEqual(value, integer)
            ? integer
            : -1;
    }

    private static boolean nearlyEqual(double left, double right) {
        return Math.abs(left - right)
            <= EPSILON * Math.max(
                1.0,
                Math.max(Math.abs(left), Math.abs(right))
            );
    }

    public enum AttemptStatus {
        MATCHED,
        NOT_MATCHED,
        INCONCLUSIVE
    }

    public record MatchAttempt(
        AttemptStatus status,
        Map<String, Expr> bindings,
        int visitedBranches,
        String limitCode
    ) {
        public MatchAttempt {
            if (status == null || bindings == null || limitCode == null) {
                throw new IllegalArgumentException(
                    "status, bindings and limitCode are required");
            }
            bindings = Map.copyOf(bindings);
            if (visitedBranches < 0) {
                throw new IllegalArgumentException(
                    "visitedBranches must not be negative");
            }
            if (status == AttemptStatus.INCONCLUSIVE
                    && limitCode.isBlank()) {
                throw new IllegalArgumentException(
                    "inconclusive attempts require a limitCode");
            }
            if (status != AttemptStatus.INCONCLUSIVE
                    && !limitCode.isEmpty()) {
                throw new IllegalArgumentException(
                    "conclusive attempts must not carry a limitCode");
            }
        }

        public boolean matched() {
            return status == AttemptStatus.MATCHED;
        }

        public boolean inconclusive() {
            return status == AttemptStatus.INCONCLUSIVE;
        }
    }

    private static final class MatchBudget {
        private final int initialBranches;
        private int remainingBranches;

        private MatchBudget(int remainingBranches) {
            this.initialBranches = remainingBranches;
            this.remainingBranches = remainingBranches;
        }

        private void consumeBranch() {
            if (remainingBranches <= 0) {
                throw new MatchLimitExceeded(
                    "COMMUTATIVE_BACKTRACKING_LIMIT");
            }
            remainingBranches--;
        }

        private int usedBranches() {
            return initialBranches - remainingBranches;
        }
    }

    private static final class MatchLimitExceeded
        extends RuntimeException {
        private final String code;

        private MatchLimitExceeded(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }

    private record Monomial(
        double coefficient,
        Map<String, Integer> powers
    ) {
        private Monomial {
            powers = Map.copyOf(new TreeMap<>(powers));
        }

        static java.util.Optional<Monomial> from(Expr expression) {
            if (expression instanceof NumberExpr number) {
                return java.util.Optional.of(
                    new Monomial(number.value(), Map.of()));
            }
            if (expression instanceof VariableExpr variable) {
                return java.util.Optional.of(
                    new Monomial(1.0, Map.of(variable.name(), 1)));
            }
            if (!(expression instanceof BinaryExpr binary)) {
                return java.util.Optional.empty();
            }
            if (binary.operator() == BinaryOperator.MUL
                    || binary.operator() == BinaryOperator.DIV) {
                var left = from(binary.left());
                var right = from(binary.right());
                if (left.isEmpty() || right.isEmpty()) {
                    return java.util.Optional.empty();
                }
                if (binary.operator() == BinaryOperator.DIV
                        && nearlyEqual(right.get().coefficient, 0.0)) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(
                    binary.operator() == BinaryOperator.MUL
                        ? left.get().multiply(right.get())
                        : left.get().divide(right.get()));
            }
            if (binary.operator() == BinaryOperator.POW
                    && binary.right() instanceof NumberExpr exponentExpr) {
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
            other.powers.forEach((name, exponent) ->
                result.merge(name, exponent, Integer::sum));
            return new Monomial(coefficient * other.coefficient, result);
        }

        Monomial divide(Monomial other) {
            Map<String, Integer> result = new TreeMap<>(powers);
            other.powers.forEach((name, exponent) ->
                result.merge(name, -exponent, Integer::sum));
            result.entrySet().removeIf(entry -> entry.getValue() == 0);
            return new Monomial(coefficient / other.coefficient, result);
        }

        Monomial pow(int exponent) {
            Map<String, Integer> result = new TreeMap<>();
            powers.forEach((name, power) ->
                result.put(name, power * exponent));
            return new Monomial(
                Math.pow(coefficient, exponent),
                result
            );
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
                result.put(
                    entry.getKey(),
                    entry.getValue() / exponent
                );
            }
            double rootCoefficient = coefficient < 0.0
                ? -Math.pow(-coefficient, 1.0 / exponent)
                : Math.pow(coefficient, 1.0 / exponent);
            if (!nearlyEqual(
                    Math.pow(rootCoefficient, exponent),
                    coefficient)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(
                new Monomial(rootCoefficient, result));
        }

        boolean equivalentTo(Monomial other) {
            return nearlyEqual(coefficient, other.coefficient)
                && powers.equals(other.powers);
        }

        boolean isConstant(double expected) {
            return powers.isEmpty()
                && nearlyEqual(coefficient, expected);
        }

        Expr toExpr() {
            Expr result = null;
            if (!nearlyEqual(coefficient, 1.0) || powers.isEmpty()) {
                result = new NumberExpr(coefficient);
            }
            for (var entry : powers.entrySet()) {
                Expr factor = new VariableExpr(entry.getKey());
                if (entry.getValue() != 1) {
                    factor = new BinaryExpr(
                        factor,
                        BinaryOperator.POW,
                        new NumberExpr(entry.getValue())
                    );
                }
                result = result == null
                    ? factor
                    : new BinaryExpr(
                        result,
                        BinaryOperator.MUL,
                        factor
                    );
            }
            return result == null ? new NumberExpr(1.0) : result;
        }
    }
}
