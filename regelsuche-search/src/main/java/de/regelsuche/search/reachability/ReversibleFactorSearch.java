package de.regelsuche.search.reachability;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.search.reachability.ReversibleFactorFragment.Allowance;
import de.regelsuche.search.reachability.ReversibleFactorFragment.Counter;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Target-directed path search on the reciprocal additive factor/distribution fragment. */
public final class ReversibleFactorSearch {
    public static final String INVENTORY = ReversibleFactorFragment.ID;
    public enum Mode { ONE_SIDED, BIDIRECTIONAL, ITERATIVE_DEEPENING }
    public enum Status { REACHED, NO_PATH_WITHIN_DEPTH, WORK_EXHAUSTED }
    public record Count(int calls, long generated) {}
    public record Work(Count forward, Count reverse, Count replay) {
        public int totalCalls() { return forward.calls + reverse.calls + replay.calls; }
    }
    public record State(String expression, AssumptionSignature assumptions) {}
    public record Step(State from, State to, Transformation primitive) {}
    public record Result(Status status, List<Step> witness, Work work) {
        public Result { witness = List.copyOf(witness); }
    }
    public record Closure(BoundedReachabilityOracle.Result oracle, Count work) {}

    public Result search(String source, String target, AssumptionSignature assumptions, int depth, int callLimit, Mode mode) {
        if (depth < 0) throw new IllegalArgumentException("negative path depth");
        Objects.requireNonNull(mode, "mode");
        var fragment = new ReversibleFactorFragment();
        var context = BoundedReachabilityOracle.normalizeSignature(assumptions);
        var start = new State(fragment.endpoint(source), context);
        var end = new State(fragment.endpoint(target), context);
        var allowance = new Allowance(callLimit);
        var forward = new Frontier(start, new Counter(allowance));
        var reverse = new Frontier(end, new Counter(allowance));
        var replay = new Counter(allowance);
        try {
            List<Step> path = mode == Mode.ITERATIVE_DEEPENING
                ? IterativeFactorSearch.find(fragment, start, end, depth, forward.work)
                : breadthFirstPath(fragment, forward, reverse, depth, mode);
            if (path == null) return result(Status.NO_PATH_WITHIN_DEPTH, List.of(), forward, reverse, replay);
            if (!replays(new ReversibleFactorFragment(), start, end, path, replay)) throw new IllegalStateException("invalid reconstructed path");
            return result(Status.REACHED, path, forward, reverse, replay);
        } catch (BoundedReachabilityOracle.EngineWorkExhausted exhausted) {
            return result(Status.WORK_EXHAUSTED, List.of(), forward, reverse, replay);
        }
    }

    private static List<Step> breadthFirstPath(ReversibleFactorFragment fragment, Frontier forward,
            Frontier reverse, int depth, Mode mode) {
        State meeting = find(fragment, forward, reverse, depth, mode);
        return meeting == null ? null : join(forward, reverse, meeting);
    }

    private static Result result(Status status, List<Step> path, Frontier left, Frontier right, Counter replay) {
        return new Result(status, path, new Work(left.work.snapshot(), right.work.snapshot(), replay.snapshot()));
    }

    /** Exhaust the entire bounded closure. The four rules preserve variable names; the fresh target is unreachable. */
    public Closure closure(String source, AssumptionSignature assumptions, BoundedReachabilityOracle.Budget limits, int callLimit) {
        var fragment = new ReversibleFactorFragment();
        String start = fragment.endpoint(source);
        String sentinel = "UnreachableDiagnosticSymbol";
        while (start.contains(sentinel)) sentinel += "X";
        var counter = new Counter(new Allowance(callLimit));
        var oracle = new BoundedReachabilityOracle().analyze(start, sentinel, assumptions,
            expr -> fragment.neighbors(expr, counter).stream().map(ReversibleFactorFragment.Arc::outgoing).toList(), limits);
        return new Closure(oracle, counter.snapshot());
    }

    private static State find(ReversibleFactorFragment fragment, Frontier left, Frontier right, int depth, Mode mode) {
        if (left.root.equals(right.root)) return left.root;
        for (int level = 0; level < depth; level++) {
            boolean backward = mode == Mode.BIDIRECTIONAL && level % 2 == 1;
            Frontier active = backward ? right : left;
            Frontier other = backward ? left : right;
            State found = active.advance(fragment, other, backward);
            if (found != null) return found;
            if (active.layer.isEmpty()) break;
        }
        return null;
    }

    private static final class Frontier {
        final State root;
        final Counter work;
        final Map<State, Step> parent = new LinkedHashMap<>();
        List<State> layer;
        Frontier(State root, Counter work) {
            this.root = root;
            this.work = work;
            parent.put(root, null);
            layer = List.of(root);
        }
        State advance(ReversibleFactorFragment fragment, Frontier other, boolean backward) {
            List<State> next = new ArrayList<>();
            for (State state : layer) {
                for (var arc : fragment.neighbors(state.expression, work)) {
                    var neighbor = new State(arc.outgoing().transformedExpression(), state.assumptions);
                    if (parent.containsKey(neighbor)) continue;
                    parent.put(neighbor, backward ? new Step(neighbor, state, arc.returning()) : new Step(state, neighbor, arc.outgoing()));
                    if (other.parent.containsKey(neighbor)) return neighbor;
                    next.add(neighbor);
                }
            }
            layer = next;
            return null;
        }
    }

    private static List<Step> join(Frontier left, Frontier right, State meeting) {
        List<Step> result = new ArrayList<>();
        for (Step s = left.parent.get(meeting); s != null; s = left.parent.get(s.from)) result.add(s);
        Collections.reverse(result);
        for (Step s = right.parent.get(meeting); s != null; s = right.parent.get(s.to)) result.add(s);
        return result;
    }

    public boolean replays(String source, String target, AssumptionSignature assumptions, List<Step> path) {
        var fragment = new ReversibleFactorFragment();
        var context = BoundedReachabilityOracle.normalizeSignature(assumptions);
        return replays(fragment, new State(fragment.endpoint(source), context), new State(fragment.endpoint(target), context),
            path, new Counter(new Allowance(Integer.MAX_VALUE)));
    }

    private static boolean replays(ReversibleFactorFragment fragment, State source, State target, List<Step> path, Counter counter) {
        State current = source;
        for (Step step : path) {
            if (!current.equals(step.from) || !source.assumptions.equals(step.to.assumptions)
                    || !step.to.expression.equals(step.primitive.transformedExpression())) return false;
            if (fragment.neighbors(current.expression, counter).stream().noneMatch(arc -> arc.outgoing().equals(step.primitive))) return false;
            current = step.to;
        }
        return current.equals(target);
    }
}
