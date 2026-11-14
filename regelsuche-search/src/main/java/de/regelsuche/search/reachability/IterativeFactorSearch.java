package de.regelsuche.search.reachability;

import de.regelsuche.search.reachability.ReversibleFactorFragment.Arc;
import de.regelsuche.search.reachability.ReversibleFactorFragment.Counter;
import de.regelsuche.search.reachability.ReversibleFactorSearch.State;
import de.regelsuche.search.reachability.ReversibleFactorSearch.Step;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

/** Exact-path cycle detection; all depth passes spend the same forward allowance. */
final class IterativeFactorSearch {
    private IterativeFactorSearch() { }

    static List<Step> find(ReversibleFactorFragment fragment, State source, State target, int depth, Counter work) {
        if (source.equals(target)) return List.of();
        for (int limit = 1; limit <= depth; limit++) {
            Pass pass = searchToDepth(fragment, source, target, limit, work);
            if (pass.path() != null || !pass.touchedLimit() || limit == depth) return pass.path();
        }
        return null;
    }

    private record Frame(State state, Iterator<Arc> arcs) { }
    private record Pass(List<Step> path, boolean touchedLimit) { }

    private static Pass searchToDepth(ReversibleFactorFragment fragment, State source, State target,
            int limit, Counter work) {
        var path = new ArrayList<Step>();
        var ancestors = new HashSet<State>();
        var stack = new ArrayDeque<Frame>();
        ancestors.add(source);
        stack.push(expand(fragment, source, work));
        boolean touchedLimit = false;
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (!frame.arcs().hasNext()) {
                stack.pop();
                ancestors.remove(frame.state());
                if (!path.isEmpty()) path.removeLast();
                continue;
            }
            var primitive = frame.arcs().next().outgoing();
            var next = new State(primitive.transformedExpression(), source.assumptions());
            if (ancestors.contains(next)) continue;
            path.add(new Step(frame.state(), next, primitive));
            if (next.equals(target)) return new Pass(List.copyOf(path), false);
            if (path.size() == limit) {
                touchedLimit = true;
                path.removeLast();
            } else {
                ancestors.add(next);
                stack.push(expand(fragment, next, work));
            }
        }
        return new Pass(null, touchedLimit);
    }

    private static Frame expand(ReversibleFactorFragment fragment, State state, Counter work) {
        return new Frame(state, fragment.neighbors(state.expression(), work).iterator());
    }
}
