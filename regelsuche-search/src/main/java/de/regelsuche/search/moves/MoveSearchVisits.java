package de.regelsuche.search.moves;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongConsumer;

/** Per-search admitted labels. A proposal enters this index only after successful admission. */
final class MoveSearchVisits<S extends SearchExecution.Position<?>> implements de.regelsuche.retention.RetainedGraph.View {
    @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(live);v.reference(positions);v.reference(charge);}
    private record Label<S extends SearchExecution.Position<?>>(S state, long theoryWork) implements de.regelsuche.retention.RetainedGraph.View {
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(state);}
        boolean noMoreExpensiveThan(Label<S> other) {
            return state.searchDepth() <= other.state.searchDepth()
                && state.primitiveDepth() <= other.state.primitiveDepth()
                && theoryWork <= other.theoryWork
                && state.complexityDebt() <= other.state.complexityDebt();
        }
    }
    private record Position(Object expression, List<String> assumptions, Set<String> capabilities) implements de.regelsuche.retention.RetainedGraph.View {
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(expression);v.reference(assumptions);v.reference(capabilities);}
        static Position of(SearchExecution.Position<?> state) {
            return new Position(state.expression(), state.assumptions(), state.capabilities());
        }
    }
    private final boolean stateLocal;
    private final LongConsumer charge;
    private final Set<Label<S>> live = new HashSet<>();
    private final Map<Position, List<Label<S>>> positions = new HashMap<>();

    MoveSearchVisits(SearchContinuationContract contract, LongConsumer charge) {
        stateLocal = Objects.requireNonNull(contract) == SearchContinuationContract.DECLARED_STATE_LOCAL;
        this.charge = Objects.requireNonNull(charge);
    }

    boolean stateLocal() { return stateLocal; }

    MoveSearch.Decision rejection(S state, long theoryWork, long remaining) {
        var proposed = new Label<>(state, theoryWork);
        if (!stateLocal) return live.contains(proposed) ? MoveSearch.Decision.DUPLICATE : null;
        if (remaining-- <= 0) return MoveSearch.Decision.WORK_LIMIT;
        charge.accept(1);
        for (var admitted : positions.getOrDefault(Position.of(state), List.of())) {
            if (remaining-- <= 0) return MoveSearch.Decision.WORK_LIMIT;
            charge.accept(1);
            if (admitted.noMoreExpensiveThan(proposed)) {
                return admitted.equals(proposed) ? MoveSearch.Decision.DUPLICATE : MoveSearch.Decision.DOMINATED;
            }
        }
        return null;
    }

    boolean add(S state, long theoryWork, long remaining) {
        var admitted = new Label<>(state, theoryWork);
        if (stateLocal) {
            if (remaining-- <= 0) return false;
            charge.accept(1);
            Position position = Position.of(state);
            var alternatives = positions.get(position);
            if (alternatives == null) {
                alternatives = new ArrayList<>();
                positions.put(position, alternatives);
            } else if (!removeDominated(admitted, alternatives, remaining)) {
                return false;
            }
            alternatives.add(admitted);
        }
        live.add(admitted);
        return true;
    }

    private boolean removeDominated(Label<S> admitted, List<Label<S>> alternatives, long remaining) {
        // Establish the complete replacement and afford all removals before
        // invalidating old frontier tickets or publishing the new label.
        List<Integer> dominated = List.of();
        for (int i = 0; i < alternatives.size(); i++) {
            if (remaining-- <= 0) return false;
            charge.accept(1);
            if (admitted.noMoreExpensiveThan(alternatives.get(i))) {
                if (dominated.isEmpty()) dominated = new ArrayList<>();
                dominated.add(i);
            }
        }
        if (remaining < dominated.size()) return false;
        for (int i = dominated.size() - 1; i >= 0; i--) {
            charge.accept(1);
            live.remove(alternatives.remove(dominated.get(i).intValue()));
        }
        return true;
    }

    /** Invalidated tickets must not open or resume a picker, or consume another state slot. */
    boolean current(S state, long theoryWork) {
        if (!stateLocal) return true;
        charge.accept(1);
        return live.contains(new Label<>(state, theoryWork));
    }
}
