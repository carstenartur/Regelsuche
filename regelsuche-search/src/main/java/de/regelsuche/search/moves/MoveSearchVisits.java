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
final class MoveSearchVisits {
    private record Label(MoveState state, long theoryWork) {
        boolean noMoreExpensiveThan(Label other) {
            return state.searchDepth() <= other.state.searchDepth()
                && state.primitiveDepth() <= other.state.primitiveDepth()
                && theoryWork <= other.theoryWork
                && state.complexityDebt() <= other.state.complexityDebt();
        }
    }
    private record Position(String expression, List<String> assumptions, Set<String> capabilities) {
        static Position of(MoveState state) {
            return new Position(state.expression(), state.assumptions(), state.capabilities());
        }
    }
    private final boolean stateLocal;
    private final LongConsumer charge;
    private final Set<Label> live = new HashSet<>();
    private final Map<Position, List<Label>> positions = new HashMap<>();

    MoveSearchVisits(SearchContinuationContract contract, LongConsumer charge) {
        stateLocal = Objects.requireNonNull(contract) == SearchContinuationContract.DECLARED_STATE_LOCAL;
        this.charge = Objects.requireNonNull(charge);
    }

    MoveSearch.Decision rejection(MoveState state, long theoryWork) {
        var proposed = new Label(state, theoryWork);
        if (!stateLocal) return live.contains(proposed) ? MoveSearch.Decision.DUPLICATE : null;
        charge.accept(1);
        for (var admitted : positions.getOrDefault(Position.of(state), List.of())) {
            charge.accept(1);
            if (admitted.noMoreExpensiveThan(proposed)) {
                return admitted.equals(proposed) ? MoveSearch.Decision.DUPLICATE : MoveSearch.Decision.DOMINATED;
            }
        }
        return null;
    }

    void add(MoveState state, long theoryWork) {
        var admitted = new Label(state, theoryWork);
        if (stateLocal) {
            charge.accept(1);
            var alternatives = positions.computeIfAbsent(Position.of(state), ignored -> new ArrayList<>());
            var iterator = alternatives.iterator();
            while (iterator.hasNext()) {
                charge.accept(1);
                var previous = iterator.next();
                if (admitted.noMoreExpensiveThan(previous)) {
                    iterator.remove();
                    live.remove(previous);
                    charge.accept(1);
                }
            }
            alternatives.add(admitted);
        }
        live.add(admitted);
    }

    /** Invalidated tickets must not open or resume a picker, or consume another state slot. */
    boolean current(MoveState state, long theoryWork) {
        if (!stateLocal) return true;
        charge.accept(1);
        return live.contains(new Label(state, theoryWork));
    }
}
