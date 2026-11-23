package de.regelsuche.egraph;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.transform.PatternExpr;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Exact matching of scalar AC operands, including repeated class bindings. */
public final class FlatAcMatchPlan {
    public static final String VERSION = "exact-scalar-flat-ac/v1";
    public static final String CONTEXTUAL_VERSION = "exact-scalar-flat-ac/contextual/v1";
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

    public record Result(List<EGraphPatternMatcher.Match> matches, int work, boolean complete, int candidateRoots, int nodesScanned, int tupleAttempts, int duplicateBindings) {
        public Result(List<EGraphPatternMatcher.Match> matches,int work,boolean complete) { this(matches,work,complete,0,0,0,0); }
        public Result { matches = List.copyOf(matches); }
    }

    record ContextMatch(EGraphPatternMatcher.Match match, List<EClassId> remainder) {
        ContextMatch { remainder = List.copyOf(remainder); }
    }

    record ContextResult(List<ContextMatch> matches, int work, boolean complete, int candidateRoots,
                         int nodesScanned, int tupleAttempts, int duplicateBindings) { }

    Result match(EGraph graph, int limit) {
        if (!graph.usesArithmeticAc()) throw new IllegalArgumentException("flat AC plans require the exact scalar AC graph");
        if (limit < 0 || limit > MAX_WORK) throw new IllegalArgumentException("invalid flat AC work limit");
        return matchRoots(graph, graph.classesWith(signature), limit);
    }

    Result matchRoots(EGraph graph, Collection<EClassId> roots, int limit) {
        var result = matchRoots(graph, roots, limit, false);
        return new Result(result.matches().stream().map(ContextMatch::match).toList(), result.work(),
            result.complete(), result.candidateRoots(), result.nodesScanned(), result.tupleAttempts(),
            result.duplicateBindings());
    }

    ContextResult matchRoots(EGraph graph, Collection<EClassId> roots, int limit, boolean allowRemainder) {
        if (!graph.usesArithmeticAc() || limit < 0 || limit > MAX_WORK) throw new IllegalArgumentException("invalid native AC plan scope");
        return new Scan(graph, limit, allowRemainder).run(roots);
    }

    private final class Scan {
        private final EGraph graph;
        private final int limit;
        private final boolean allowRemainder;
        private int work, candidateRoots, nodesScanned, tupleAttempts, duplicateBindings;
        private boolean complete = true;
        private final LinkedHashSet<ContextMatch> matches = new LinkedHashSet<>();

        private Scan(EGraph graph, int limit, boolean allowRemainder) {
            this.graph = graph;
            this.limit = limit;
            this.allowRemainder = allowRemainder;
        }

        private boolean spend() {
            if (work == limit) {
                complete = false;
                return false;
            }
            work++;
            return true;
        }

        private ContextResult run(Collection<EClassId> roots) {
            for (var candidate : roots) {
                if (!spend()) break;
                candidateRoots++;
                var root = graph.find(candidate);
                for (var node : graph.classOrThrow(root).nodes()) {
                    if (!spend()) break;
                    nodesScanned++;
                    int remainderSize = node.children().size() - signature.arity();
                    if (!signature.symbol().equals(node.symbol()) || remainderSize < 0
                            || (!allowRemainder && remainderSize > 0) || node.children().size() > MAX_ARITY) continue;
                    var counts = new TreeMap<EClassId, Integer>();
                    for (var child : node.children()) {
                        if (!spend()) break;
                        counts.merge(graph.find(child), 1, Integer::sum);
                    }
                    if (complete) {
                        var ids = List.copyOf(counts.keySet());
                        int[] remaining = counts.values().stream().mapToInt(Integer::intValue).toArray();
                        bind(root, ids, remaining, 0, new LinkedHashMap<>(), remainderSize);
                    }
                    if (!complete) break;
                }
                if (!complete) break;
            }
            return new ContextResult(List.copyOf(matches), work, complete, candidateRoots, nodesScanned, tupleAttempts, duplicateBindings);
        }

        private void bind(EClassId root, List<EClassId> ids, int[] remaining,
                          int depth, Map<String, EClassId> bindings, int remainderSize) {
            if (depth == slots.size()) {
                retain(root, ids, remaining, bindings, remainderSize);
                return;
            }
            var slot = slots.get(depth);
            for (int i = 0; i < ids.size(); i++) {
                if (!spend()) return;
                tupleAttempts++;
                if (remaining[i] < slot.occurrences()) continue;
                remaining[i] -= slot.occurrences();
                bindings.put(slot.name(), ids.get(i));
                bind(root, ids, remaining, depth + 1, bindings, remainderSize);
                bindings.remove(slot.name());
                remaining[i] += slot.occurrences();
                if (!complete) return;
            }
        }

        private void retain(EClassId root, List<EClassId> ids, int[] remaining,
                            Map<String, EClassId> bindings, int remainderSize) {
            List<EClassId> remainder = List.of();
            if (remainderSize > 0) {
                remainder = new ArrayList<>(remainderSize);
                for (int i = 0; i < ids.size(); i++) {
                    if (!spend()) return;
                    for (int occurrence = 0; occurrence < remaining[i]; occurrence++) {
                        if (!spend()) return;
                        remainder.add(ids.get(i));
                    }
                }
            }
            if (!matches.add(new ContextMatch(new EGraphPatternMatcher.Match(root, bindings), remainder))) {
                duplicateBindings++;
            }
        }
    }

    @Override public boolean equals(Object other) {
        return other instanceof FlatAcMatchPlan plan && signature.equals(plan.signature) && slots.equals(plan.slots);
    }

    @Override public int hashCode() { return Objects.hash(signature, slots); }
}
