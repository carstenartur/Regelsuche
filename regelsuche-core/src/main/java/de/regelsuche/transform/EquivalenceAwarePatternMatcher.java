package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        MatchBudget budget = new MatchBudget(
            maxBacktrackingBranches, profile.inferAlgebraicBindings());
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
        } catch (BoundedExactMonomial.LimitExceeded limit) {
            return new MatchAttempt(
                AttemptStatus.INCONCLUSIVE, original, budget.usedBranches(), limit.code);
        } catch (MatchLimitExceeded limit) {
            return new MatchAttempt(
                AttemptStatus.INCONCLUSIVE,
                original,
                budget.usedBranches(),
                limit.code
            );
        }
    }

    private sealed interface MatchTask permits PairTask, PermutationTask {}

    private record PairTask(PatternExpr pattern, Expr expression, MatchTask next) implements MatchTask {}

    private record PermutationTask(List<PatternExpr> patterns, List<Expr> expressions,
            int patternIndex, int expressionIndex, MatchTask next) implements MatchTask {}

    private record Alternative(MatchTask pending, Map<String, Expr> bindings) {}

    /**
     * Retain the entire continuation when choosing a commutative binding. A
     * later argument may reject an earlier locally successful choice. Explicit
     * pending tasks avoid growing the Java stack with ordered sibling count;
     * alternative branches retain isolated bindings and share the same budget.
     */
    private static boolean matchInternal(
        PatternExpr pattern,
        Expr expression,
        Map<String, Expr> bindings,
        RecognitionProfile profile,
        MatchBudget budget
    ) {
        var alternatives = new ArrayDeque<Alternative>();
        alternatives.push(new Alternative(new PairTask(pattern, expression, null), new HashMap<>(bindings)));
        search: while (!alternatives.isEmpty()) {
            var alternative = alternatives.pop();
            MatchTask pending = alternative.pending();
            Map<String, Expr> current = alternative.bindings();
            while (pending != null) {
                if (pending instanceof PermutationTask permutation) {
                    if (permutation.patternIndex() == permutation.patterns().size()) {
                        if (!permutation.expressions().isEmpty()) continue search;
                        pending = permutation.next();
                        continue;
                    }
                    PatternExpr operand = permutation.patterns().get(permutation.patternIndex());
                    int index = permutation.expressionIndex();
                    while (index < permutation.expressions().size()
                            && !couldStructurallyMatch(operand, permutation.expressions().get(index), profile, budget)) {
                        index++;
                    }
                    if (index == permutation.expressions().size()) continue search;
                    // Retain the next choice lazily instead of copying every permutation.
                    if (index + 1 < permutation.expressions().size()) {
                        alternatives.push(new Alternative(new PermutationTask(
                            permutation.patterns(), permutation.expressions(), permutation.patternIndex(),
                            index + 1, permutation.next()), new HashMap<>(current)));
                    }
                    budget.consumeBranch();
                    Expr candidate = permutation.expressions().get(index);
                    var remaining = new ArrayList<>(permutation.expressions());
                    remaining.remove(index);
                    pending = new PairTask(operand, candidate, new PermutationTask(
                        permutation.patterns(), remaining, permutation.patternIndex() + 1, 0, permutation.next()));
                    continue;
                }

                PairTask pair = (PairTask) pending;
                PatternExpr node = pair.pattern();
                Expr candidate = pair.expression();
                pending = pair.next();
                if (node instanceof PatternExpr.Placeholder placeholder) {
                    Expr bound = current.get(placeholder.name());
                    if (bound == null) current.put(placeholder.name(), candidate);
                    else if (!equivalent(bound, candidate, profile, budget)) continue search;
                    continue;
                }
                if (node instanceof PatternExpr.LiteralNumber number) {
                    boolean matches = candidate instanceof NumberExpr literal
                        ? literal.value().equals(number.value())
                        : profile.inferAlgebraicBindings()
                            && BoundedExactMonomial.from(candidate, budget.algebraic)
                                .map(monomial -> monomial.isConstant(number.value())).orElse(false);
                    if (!matches) continue search;
                    continue;
                }
                if (node instanceof PatternExpr.LiteralVariable variable) {
                    if (!(candidate instanceof VariableExpr literal) || !literal.name().equals(variable.name())) {
                        continue search;
                    }
                    continue;
                }
                if (node instanceof PatternExpr.Function function) {
                    if (!(candidate instanceof FunctionExpr concrete)
                            || !concrete.name().equals(function.name())
                            || concrete.arguments().size() != function.arguments().size()) {
                        continue search;
                    }
                    for (int index = function.arguments().size() - 1; index >= 0; index--) {
                        pending = new PairTask(function.arguments().get(index), concrete.arguments().get(index), pending);
                    }
                    continue;
                }

                PatternExpr.Operation operation = (PatternExpr.Operation) node;
                if (profile.inferAlgebraicBindings()) {
                    if (allPlaceholdersBound(operation, current)
                            && equivalent(operation.instantiate(current), candidate, profile, budget)) {
                        continue;
                    }
                    Map<String, Expr> inferred = new HashMap<>(current);
                    if (tryInferPowerBinding(operation, candidate, inferred, profile, budget)) {
                        current = inferred;
                        continue;
                    }
                }
                if (!(candidate instanceof BinaryExpr binary) || binary.operator() != operation.operator()) {
                    continue search;
                }
                if (!profile.isAssociative(operation.operator())) {
                    pending = new PairTask(operation.left(), binary.left(),
                        new PairTask(operation.right(), binary.right(), pending));
                    continue;
                }

                List<PatternExpr> patternOperands = new ArrayList<>();
                flattenPattern(operation, operation.operator(), patternOperands);
                List<Expr> expressionOperands = new ArrayList<>();
                flattenExpression(binary, operation.operator(), expressionOperands);
                if (patternOperands.size() != expressionOperands.size()) continue search;
                if (profile.isCommutative(operation.operator())) {
                    if (patternOperands.size() > DEFAULT_MAX_COMMUTATIVE_OPERANDS) {
                        throw new MatchLimitExceeded("COMMUTATIVE_OPERAND_LIMIT");
                    }
                    patternOperands.sort(Comparator
                        .comparingInt(EquivalenceAwarePatternMatcher::bindingPriority).reversed());
                    pending = new PermutationTask(patternOperands, expressionOperands, 0, 0, pending);
                } else {
                    for (int index = patternOperands.size() - 1; index >= 0; index--) {
                        pending = new PairTask(patternOperands.get(index), expressionOperands.get(index), pending);
                    }
                }
            }
            bindings.clear();
            bindings.putAll(current);
            return true;
        }
        return false;
    }

    private static boolean tryInferPowerBinding(
        PatternExpr.Operation operation,
        Expr expression,
        Map<String, Expr> bindings,
        RecognitionProfile profile,
        MatchBudget budget
    ) {
        if (operation.operator() != BinaryOperator.POW
                || !(operation.left()
                    instanceof PatternExpr.Placeholder placeholder)
                || !(operation.right()
                    instanceof PatternExpr.LiteralNumber exponentLiteral)) {
            return false;
        }
        int exponent = BoundedExactMonomial.positiveInteger(exponentLiteral.value());
        if (exponent < 1) {
            return false;
        }
        Expr existing = bindings.get(placeholder.name());
        if (existing != null) {
            return equivalent(
                operation.instantiate(bindings),
                expression,
                profile,
                budget
            );
        }
        var monomial = BoundedExactMonomial.from(expression, budget.algebraic);
        if (monomial.isEmpty()) {
            return false;
        }
        var root = monomial.get().exactRoot(exponent, budget.algebraic);
        if (root.isEmpty()) {
            return false;
        }
        bindings.put(placeholder.name(), root.get().toExpr());
        return equivalent(
            operation.instantiate(bindings),
            expression,
            profile,
            budget
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
        RecognitionProfile profile,
        MatchBudget budget
    ) {
        if (left.equals(right)) {
            return true;
        }
        if (!profile.inferAlgebraicBindings()) {
            return false;
        }
        var leftMonomial = BoundedExactMonomial.from(left, budget.algebraic);
        var rightMonomial = BoundedExactMonomial.from(right, budget.algebraic);
        return leftMonomial.isPresent()
            && rightMonomial.isPresent()
            && leftMonomial.get().equivalentTo(rightMonomial.get());
    }

    private static boolean couldStructurallyMatch(
        PatternExpr pattern,
        Expr expression,
        RecognitionProfile profile,
        MatchBudget budget
    ) {
        if (pattern instanceof PatternExpr.Placeholder) {
            return true;
        }
        if (pattern instanceof PatternExpr.LiteralNumber) {
            return expression instanceof NumberExpr
                || profile.inferAlgebraicBindings()
                    && BoundedExactMonomial.from(expression, budget.algebraic).isPresent();
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
            return BoundedExactMonomial.from(expression, budget.algebraic).isPresent()
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
        private final BoundedExactMonomial.Budget algebraic;
        private final int initialBranches;
        private int remainingBranches;

        private MatchBudget(int remainingBranches, boolean inferAlgebraicBindings) {
            this.algebraic = inferAlgebraicBindings ? new BoundedExactMonomial.Budget() : null;
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

}
