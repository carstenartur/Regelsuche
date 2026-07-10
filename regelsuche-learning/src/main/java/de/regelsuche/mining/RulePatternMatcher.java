package de.regelsuche.mining;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Structural matcher for learned macro-rule source patterns. */
public class RulePatternMatcher {
    private final RulePatternParser patternParser = new RulePatternParser();
    private final ExpressionParser expressionParser = new ExpressionParser();

    public Optional<Map<String, Expr>> match(String pattern, String expression) {
        try {
            RulePatternNode patternNode = patternParser.parse(pattern);
            Expr expressionNode = expressionParser.parseTerm(expression);
            Map<String, Expr> bindings = new HashMap<>();
            if (!match(patternNode, expressionNode, bindings)) {
                return Optional.empty();
            }
            return Optional.of(Map.copyOf(bindings));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public Optional<Map<String, Expr>> match(RulePatternNode patternNode, String expression) {
        try {
            Expr expressionNode = expressionParser.parseTerm(expression);
            Map<String, Expr> bindings = new HashMap<>();
            if (!match(patternNode, expressionNode, bindings)) {
                return Optional.empty();
            }
            return Optional.of(Map.copyOf(bindings));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private boolean match(RulePatternNode pattern, Expr expression, Map<String, Expr> bindings) {
        if (pattern instanceof PatternVariable variable) {
            Expr existing = bindings.get(variable.name());
            if (existing == null) {
                bindings.put(variable.name(), expression);
                return true;
            }
            return existing.equals(expression);
        }
        if (pattern instanceof PatternNumber number) {
            return expression instanceof NumberExpr value && Double.compare(value.value(), number.value()) == 0;
        }
        if (pattern instanceof PatternFunction function) {
            if (!(expression instanceof FunctionExpr value)
                || !function.name().equals(value.name())
                || function.arguments().size() != value.arguments().size()) {
                return false;
            }
            for (int index = 0; index < function.arguments().size(); index++) {
                if (!match(function.arguments().get(index), value.arguments().get(index), bindings)) {
                    return false;
                }
            }
            return true;
        }
        PatternBinary binary = (PatternBinary) pattern;
        if (!(expression instanceof BinaryExpr value) || binary.op() != value.operator()) {
            return false;
        }
        Map<String, Expr> direct = new HashMap<>(bindings);
        if (match(binary.left(), value.left(), direct) && match(binary.right(), value.right(), direct)) {
            bindings.clear();
            bindings.putAll(direct);
            return true;
        }
        if (isCommutative(binary.op())) {
            Map<String, Expr> swapped = new HashMap<>(bindings);
            if (match(binary.left(), value.right(), swapped) && match(binary.right(), value.left(), swapped)) {
                bindings.clear();
                bindings.putAll(swapped);
                return true;
            }
        }
        return false;
    }

    private boolean isCommutative(BinaryOperator operator) {
        return operator == BinaryOperator.ADD || operator == BinaryOperator.MUL;
    }
}
