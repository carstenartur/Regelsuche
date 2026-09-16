package de.regelsuche.mining;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.mining.RulePatternMatcher.MatchResult;
import de.regelsuche.mining.RulePatternMatcher.MatchStatus;
import de.regelsuche.mining.RulePatternMatcher.MatchStep;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-invocation iterative search. Maps shared by frames are never modified. */
final class RulePatternBindingSearch {
    private record Pending(RulePatternNode pattern, Expr expression, boolean associative, Pending next) {}
    private record Frame(Pending pending, Map<String, Expr> bindings) {}

    private final long maximumWork;
    private final ArrayDeque<Frame> alternatives = new ArrayDeque<>();
    private long work;

    RulePatternBindingSearch(long maximumWork) {
        this.maximumWork = maximumWork;
    }

    MatchResult match(List<MatchStep> steps, Map<String, Expr> initialBindings) {
        try {
            Pending pending = null;
            for (int i = steps.size() - 1; i >= 0; i--) {
                var step = steps.get(i);
                pending = constraint(step.pattern(), step.expression(), false, pending);
            }
            Frame frame = new Frame(pending, initialBindings);
            while (frame != null) {
                if (frame.pending() == null) {
                    return new MatchResult(MatchStatus.MATCH, frame.bindings(), work);
                }
                charge();
                frame = advance(frame);
                if (frame == null && !alternatives.isEmpty()) {
                    frame = alternatives.pop();
                }
            }
            return outcome(MatchStatus.NO_MATCH);
        } catch (WorkLimitReached exhausted) {
            return outcome(MatchStatus.BUDGET_EXHAUSTED);
        }
    }

    private Frame advance(Frame frame) {
        var pending = frame.pending();
        var pattern = pending.pattern();
        var expression = pending.expression();
        if (pending.associative()) {
            return repeatedAssociative((PatternBinary) pattern, (BinaryExpr) expression, frame);
        }
        if (pattern instanceof PatternVariable variable) {
            return bind(variable.name(), expression, pending.next(), frame.bindings());
        }
        if (pattern instanceof PatternNumber number) {
            return expression instanceof NumberExpr value && value.equalsInteger(number.value())
                ? new Frame(pending.next(), frame.bindings()) : null;
        }
        if (pattern instanceof PatternBinary binary) {
            return expression instanceof BinaryExpr value && binary.operator() == value.operator()
                ? binary(binary, value, frame) : null;
        }
        if (pattern instanceof PatternFunction function) {
            return expression instanceof FunctionExpr value ? function(function, value, frame) : null;
        }
        return null;
    }

    private Frame bind(String name, Expr expression, Pending remaining, Map<String, Expr> bindings) {
        var existing = bindings.get(name);
        if (existing != null) {
            return existing.equals(expression) ? new Frame(remaining, bindings) : null;
        }
        var extended = new HashMap<>(bindings);
        extended.put(name, expression);
        return new Frame(remaining, extended);
    }

    private Frame binary(PatternBinary pattern, BinaryExpr expression, Frame frame) {
        var remaining = frame.pending().next();
        if (isCommutative(pattern.operator())) {
            // LIFO: direct first, then swapped, then the historical repeated-operand case.
            alternatives.push(new Frame(constraint(pattern, expression, true, remaining), frame.bindings()));
            alternatives.push(new Frame(pair(pattern, expression.right(), expression.left(), remaining), frame.bindings()));
        }
        return new Frame(pair(pattern, expression.left(), expression.right(), remaining), frame.bindings());
    }

    private Pending pair(PatternBinary pattern, Expr left, Expr right, Pending remaining) {
        var tail = constraint(pattern.right(), right, false, remaining);
        return constraint(pattern.left(), left, false, tail);
    }

    private Frame function(PatternFunction pattern, FunctionExpr expression, Frame frame) {
        if (!pattern.name().equals(expression.name()) || pattern.arguments().size() != expression.arguments().size()) {
            return null;
        }
        var pending = frame.pending().next();
        for (int i = pattern.arguments().size() - 1; i >= 0; i--) {
            pending = constraint(pattern.arguments().get(i), expression.arguments().get(i), false, pending);
        }
        return new Frame(pending, frame.bindings());
    }

    private Frame repeatedAssociative(PatternBinary pattern, BinaryExpr expression, Frame frame) {
        var operands = flattenPattern(pattern, pattern.operator());
        if (operands.size() < 3 || !(operands.getFirst() instanceof PatternVariable repeated)) {
            return null;
        }
        for (var operand : operands) {
            charge();
            if (!(operand instanceof PatternVariable variable) || !repeated.name().equals(variable.name())) {
                return null;
            }
        }
        var values = flattenExpression(expression, expression.operator());
        if (operands.size() != values.size()) {
            return null;
        }
        var pending = frame.pending().next();
        for (int i = values.size() - 1; i >= 0; i--) {
            pending = constraint(repeated, values.get(i), false, pending);
        }
        return new Frame(pending, frame.bindings());
    }

    private List<RulePatternNode> flattenPattern(RulePatternNode root, BinaryOperator operator) {
        var result = new ArrayList<RulePatternNode>();
        var nodes = new ArrayDeque<RulePatternNode>();
        nodes.push(root);
        while (!nodes.isEmpty()) {
            charge();
            var node = nodes.pop();
            if (node instanceof PatternBinary binary && binary.operator() == operator) {
                nodes.push(binary.right());
                nodes.push(binary.left());
            } else {
                result.add(node);
            }
        }
        return result;
    }

    private List<Expr> flattenExpression(Expr root, BinaryOperator operator) {
        var result = new ArrayList<Expr>();
        var nodes = new ArrayDeque<Expr>();
        nodes.push(root);
        while (!nodes.isEmpty()) {
            charge();
            var node = nodes.pop();
            if (node instanceof BinaryExpr binary && binary.operator() == operator) {
                nodes.push(binary.right());
                nodes.push(binary.left());
            } else {
                result.add(node);
            }
        }
        return result;
    }

    private Pending constraint(RulePatternNode pattern, Expr expression, boolean associative, Pending next) {
        charge();
        return new Pending(pattern, expression, associative, next);
    }

    private static boolean isCommutative(BinaryOperator operator) {
        return operator == BinaryOperator.ADD || operator == BinaryOperator.MUL;
    }

    private void charge() {
        if (work == maximumWork) {
            throw new WorkLimitReached();
        }
        work++;
    }

    private MatchResult outcome(MatchStatus status) {
        return new MatchResult(status, Map.of(), work);
    }

    private static final class WorkLimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private WorkLimitReached() {
            super(null, null, false, false);
        }
    }
}
