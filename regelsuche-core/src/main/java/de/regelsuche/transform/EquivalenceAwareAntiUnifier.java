package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Computes a bounded least-general generalization for equivalent AST examples.
 * Inputs are first normalized for AC ordering and monomial power/product form.
 */
public final class EquivalenceAwareAntiUnifier {
    public PatternExpr generalize(List<Expr> examples, RecognitionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (examples == null || examples.size() < 2) {
            throw new IllegalArgumentException("at least two examples are required");
        }
        List<Expr> normalized;
        try (RecognitionNormalizer.Session session = RecognitionNormalizer.session(profile)) {
            normalized = examples.stream().map(session::normalize).toList();
        }
        Counter counter = new Counter();
        PatternExpr result = fromExpr(normalized.get(0));
        for (int i = 1; i < normalized.size(); i++) {
            result = generalize(result, normalized.get(i), new HashMap<>(), counter);
        }
        return result;
    }

    private PatternExpr generalize(
        PatternExpr pattern,
        Expr expression,
        Map<String, PatternExpr> reused,
        Counter counter
    ) {
        if (pattern instanceof PatternExpr.LiteralNumber number
            && expression instanceof NumberExpr other
            && Double.compare(number.value(), other.value()) == 0) {
            return pattern;
        }
        if (pattern instanceof PatternExpr.LiteralVariable variable
            && expression instanceof VariableExpr other
            && variable.name().equals(other.name())) {
            return pattern;
        }
        if (pattern instanceof PatternExpr.Operation operation
            && expression instanceof BinaryExpr other
            && operation.operator() == other.operator()) {
            return PatternExpr.op(
                operation.operator(),
                generalize(operation.left(), other.left(), reused, counter),
                generalize(operation.right(), other.right(), reused, counter)
            );
        }
        if (pattern instanceof PatternExpr.Function function
            && expression instanceof FunctionExpr other
            && function.name().equals(other.name())
            && function.arguments().size() == other.arguments().size()) {
            List<PatternExpr> args = new ArrayList<>();
            for (int i = 0; i < function.arguments().size(); i++) {
                args.add(generalize(function.arguments().get(i), other.arguments().get(i), reused, counter));
            }
            return new PatternExpr.Function(function.name(), args);
        }
        String key = pattern + " :: " + expression;
        return reused.computeIfAbsent(key, ignored -> PatternExpr.var("G" + counter.next()));
    }

    private static PatternExpr fromExpr(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return PatternExpr.num(number.value());
        }
        if (expression instanceof VariableExpr variable) {
            return PatternExpr.variable(variable.name());
        }
        if (expression instanceof BinaryExpr binary) {
            return PatternExpr.op(binary.operator(), fromExpr(binary.left()), fromExpr(binary.right()));
        }
        if (expression instanceof FunctionExpr function) {
            return new PatternExpr.Function(function.name(), function.arguments().stream()
                .map(EquivalenceAwareAntiUnifier::fromExpr).toList());
        }
        throw new IllegalArgumentException("unsupported expression type: " + expression.getClass().getName());
    }

    private static final class Counter {
        private int value;
        int next() {
            return ++value;
        }
    }
}
