package de.regelsuche.transform;

import de.regelsuche.ast.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Necessary root-shape conditions only; matching and proof authority stay with the rule. */
final class RuleShapeIndex {
    private record FunctionShape(String name, int arity) {}
    private record VariableShape(String name) {}
    private record Entry(int ordinal, RewriteRule rule) {}
    private final Map<Object, List<Entry>> buckets;
    private final List<Entry> fallback;

    RuleShapeIndex(List<RewriteRule> rules) {
        var grouped = new HashMap<Object, List<Entry>>();
        var unfiltered = new ArrayList<Entry>();
        for (int i = 0; i < rules.size(); i++) {
            var entry = new Entry(i, rules.get(i));
            Object key = key(entry.rule());
            if (key == null) unfiltered.add(entry);
            else grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }
        grouped.replaceAll((key, entries) -> List.copyOf(entries));
        buckets = Map.copyOf(grouped);
        fallback = List.copyOf(unfiltered);
    }

    Iterable<RewriteRule> candidates(Expr expression) {
        Object key = key(expression);
        List<Entry> selected = key == null ? List.of() : buckets.getOrDefault(key, List.of());
        // Merge two ordered lists lazily: no per-shape duplication of wildcard rules.
        return () -> new Iterator<>() {
            private int bucketPosition;
            private int fallbackPosition;
            @Override public boolean hasNext() {
                return bucketPosition < selected.size() || fallbackPosition < fallback.size();
            }
            @Override public RewriteRule next() {
                if (!hasNext()) throw new NoSuchElementException();
                if (bucketPosition == selected.size()) return fallback.get(fallbackPosition++).rule();
                if (fallbackPosition == fallback.size()) return selected.get(bucketPosition++).rule();
                return selected.get(bucketPosition).ordinal() < fallback.get(fallbackPosition).ordinal()
                    ? selected.get(bucketPosition++).rule() : fallback.get(fallbackPosition++).rule();
            }
        };
    }

    private static Object key(RewriteRule rule) {
        // Subclasses can override matching independently of their declared source pattern.
        if (rule.getClass() != PatternRewriteRule.class) return null;
        var pattern = (PatternRewriteRule) rule;
        var profile = pattern.recognitionProfile();
        if (profile.inferAlgebraicBindings() || profile.maxEquivalenceDepth() != 0
                || !profile.recognitionRuleIds().isEmpty()) return null;
        return switch (pattern.source()) {
            case PatternExpr.Operation operation -> operation.operator();
            case PatternExpr.Function function -> new FunctionShape(function.name(), function.arguments().size());
            case PatternExpr.LiteralNumber number -> number.value();
            case PatternExpr.LiteralVariable variable -> new VariableShape(variable.name());
            case PatternExpr.Placeholder ignored -> null;
        };
    }

    private static Object key(Expr expression) {
        if (expression instanceof BinaryExpr binary) return binary.operator();
        if (expression instanceof FunctionExpr function) return new FunctionShape(function.name(), function.arguments().size());
        if (expression instanceof NumberExpr number) return number.value();
        if (expression instanceof VariableExpr variable) return new VariableShape(variable.name());
        return null;
    }
}
