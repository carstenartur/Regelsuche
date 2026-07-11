package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches pattern expressions against ASTs modulo a deliberately bounded set
 * of equivalences.
 *
 * <p>The first implementation supports associativity and commutativity for
 * selected binary operators. It flattens associative trees and performs a
 * deterministic backtracking match over commutative operands. This allows one
 * learned rule to recognize regrouped and reordered sums/products without
 * materialising every equivalent AST.</p>
 */
public final class EquivalenceAwarePatternMatcher {
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
            return bound.equals(expression);
        }
        if (pattern instanceof PatternExpr.LiteralNumber number) {
            return expression instanceof NumberExpr numberExpr && numberExpr.value() == number.value();
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
            return matchCommutative(patternOperands, expressionOperands, 0, bindings, profile);
        }
        for (int i = 0; i < patternOperands.size(); i++) {
            if (!matchInternal(patternOperands.get(i), expressionOperands.get(i), bindings, profile)) {
                return false;
            }
        }
        return true;
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

    private static void flattenPattern(
        PatternExpr pattern,
        BinaryOperator operator,
        List<PatternExpr> result
    ) {
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
}
