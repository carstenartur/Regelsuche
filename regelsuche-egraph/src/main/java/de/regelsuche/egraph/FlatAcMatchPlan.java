package de.regelsuche.egraph;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.transform.PatternExpr;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Exact whole-node matching of individual scalar AC operands, including repeated class bindings. */
public final class FlatAcMatchPlan {
    public static final String VERSION = "exact-scalar-flat-ac/v1";
    public static final int MAX_ARITY = 64;
    public static final int MAX_WORK = 100_000;
    private record Slot(String name, int occurrences) {}
    private final ENodeSignature signature;
    private final List<Slot> slots;

    private FlatAcMatchPlan(BinaryOperator operator, int arity, Map<String, Integer> counts) {
        signature = new ENodeSignature("ac:" + operator.name(), arity);
        // Bind repeated operands first. Ties are independent of syntax grouping and order.
        slots = counts.entrySet().stream().map(entry -> new Slot(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparingInt(Slot::occurrences).reversed().thenComparing(Slot::name)).toList();
    }

    public static FlatAcMatchPlan compile(PatternExpr pattern) {
        if (!(pattern instanceof PatternExpr.Operation top)
            || top.operator() != BinaryOperator.ADD && top.operator() != BinaryOperator.MUL) {
            throw new IllegalArgumentException("expected scalar AC sum or product");
        }
        var pending = new ArrayDeque<PatternExpr>();
        pending.push(pattern);
        var counts = new TreeMap<String, Integer>();
        int arity = 1;
        while (!pending.isEmpty()) {
            switch (pending.pop()) {
                case PatternExpr.Placeholder variable -> counts.merge(variable.name(), 1, Integer::sum);
                case PatternExpr.Operation operation when operation.operator() == top.operator() -> {
                    if (++arity > MAX_ARITY) throw new IllegalArgumentException("flat AC pattern exceeds operand limit");
                    pending.push(operation.left());
                    pending.push(operation.right());
                }
                default -> throw new IllegalArgumentException("flat AC operands must be placeholders");
            }
        }
        return new FlatAcMatchPlan(top.operator(), arity, counts);
    }

    public record Result(List<EGraphPatternMatcher.Match> matches, int work, boolean complete) {
        public Result { matches = List.copyOf(matches); }
    }

    Result match(EGraph graph, int limit) {
        if (!graph.usesArithmeticAc()) throw new IllegalArgumentException("flat AC plans require the exact scalar AC graph");
        if (limit < 0 || limit > MAX_WORK) throw new IllegalArgumentException("invalid flat AC work limit");
        return new Scan(graph, limit).run();
    }

    private final class Scan {
        private final EGraph graph;
        private final int limit;
        private int work;
        private boolean complete = true;
        private final LinkedHashSet<EGraphPatternMatcher.Match> matches = new LinkedHashSet<>();

        private Scan(EGraph graph, int limit) {
            this.graph = graph;
            this.limit = limit;
        }

        private boolean spend() {
            if (work == limit) {
                complete = false;
                return false;
            }
            work++;
            return true;
        }

        private Result run() {
            for (var candidate : graph.classesWith(signature)) {
                if (!spend()) break;
                var root = graph.find(candidate);
                for (var node : graph.classOrThrow(root).nodes()) {
                    if (!spend()) break;
                    if (!signature.symbol().equals(node.symbol()) || signature.arity() != node.children().size()) continue;
                    var counts = new TreeMap<EClassId, Integer>();
                    for (var child : node.children()) {
                        if (!spend()) break;
                        counts.merge(graph.find(child), 1, Integer::sum);
                    }
                    if (complete) {
                        var ids = List.copyOf(counts.keySet());
                        int[] remaining = counts.values().stream().mapToInt(Integer::intValue).toArray();
                        bind(root, ids, remaining, 0, new LinkedHashMap<>());
                    }
                    if (!complete) break;
                }
                if (!complete) break;
            }
            return new Result(List.copyOf(matches), work, complete);
        }

        private void bind(EClassId root, List<EClassId> ids, int[] remaining,
                          int depth, Map<String, EClassId> bindings) {
            if (depth == slots.size()) {
                matches.add(new EGraphPatternMatcher.Match(root, bindings));
                return;
            }
            var slot = slots.get(depth);
            for (int i = 0; i < ids.size(); i++) {
                if (!spend()) return;
                if (remaining[i] < slot.occurrences()) continue;
                remaining[i] -= slot.occurrences();
                bindings.put(slot.name(), ids.get(i));
                bind(root, ids, remaining, depth + 1, bindings);
                bindings.remove(slot.name());
                remaining[i] += slot.occurrences();
                if (!complete) return;
            }
        }
    }

    @Override public boolean equals(Object other) {
        return other instanceof FlatAcMatchPlan plan && signature.equals(plan.signature) && slots.equals(plan.slots);
    }

    @Override public int hashCode() { return Objects.hash(signature, slots); }
}
