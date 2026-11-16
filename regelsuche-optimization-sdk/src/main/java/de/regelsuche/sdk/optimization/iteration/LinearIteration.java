package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.iteration.IntegerArithmetic;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;

/** Scoped, finite, integer-ring state updates; count is a nonnegative mathematical integer. */
public record LinearIteration(SymbolId index, Expr indexStart, BigInteger indexStep, Expr count,
        List<State> states, Set<SymbolId> parameters, List<Update> updates,
        UpdateOrder order, IntegerArithmetic arithmetic) {
    public static final int MAX_STATES = de.regelsuche.math.algorithms.iteration.LinearIteration.MAX_STATES;
    public static final int MAX_PARAMETERS = de.regelsuche.math.algorithms.iteration.LinearIteration.MAX_PARAMETERS;
    public static final int MAX_INDEX_DEGREE = de.regelsuche.math.algorithms.iteration.LinearIteration.MAX_INDEX_DEGREE;
    public static final int MAX_PARAMETER_DEGREE = de.regelsuche.math.algorithms.iteration.LinearIteration.MAX_PARAMETER_DEGREE;
    public static final int MAX_TERMS = de.regelsuche.math.algorithms.iteration.LinearIteration.MAX_TERMS;
    public static final int MAX_NODES = de.regelsuche.math.algorithms.iteration.LinearIteration.MAX_NODES;
    public static final int MAX_DEPTH = de.regelsuche.math.algorithms.iteration.LinearIteration.MAX_DEPTH;
    public static final int MAX_BITS = de.regelsuche.math.algorithms.iteration.LinearIteration.MAX_BITS;

    public enum UpdateOrder { SIMULTANEOUS, SEQUENTIAL }

    public record State(SymbolId symbol, Expr initial) {
        public State {
            Objects.requireNonNull(symbol);
            Objects.requireNonNull(initial);
        }
    }

    public record Update(SymbolId state, Expr expression) {
        public Update {
            Objects.requireNonNull(state);
            Objects.requireNonNull(expression);
        }
    }

    public LinearIteration {
        Objects.requireNonNull(index);
        Objects.requireNonNull(indexStep);
        Objects.requireNonNull(order);
        Objects.requireNonNull(arithmetic);
        // Check sizes before mapping the legacy records.
        if (states.isEmpty() || states.size() > MAX_STATES || updates.size() != states.size()) {
            throw new IllegalArgumentException("STATE_DIMENSION");
        }
        if (parameters.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException("PARAMETER_LIMIT");
        }
        states = List.copyOf(states);
        parameters = Set.copyOf(parameters);
        updates = List.copyOf(updates);
        var source = LinearIterationAdapter.source(index, indexStart, indexStep, count, states, parameters, updates, order, arithmetic);
        states = new CheckedStates(states, source);
    }

    @Override
    public List<State> states() {
        return ((CheckedStates) states).values;
    }

    de.regelsuche.math.algorithms.iteration.LinearIteration mathSource() {
        return ((CheckedStates) states).source;
    }

    // Keep the checked source with the immutable state component of the public record.
    private static final class CheckedStates extends AbstractList<State> implements RandomAccess {
        private final List<State> values;
        private final de.regelsuche.math.algorithms.iteration.LinearIteration source;

        private CheckedStates(List<State> values, de.regelsuche.math.algorithms.iteration.LinearIteration source) {
            this.values = values;
            this.source = source;
        }

        @Override
        public State get(int index) {
            return values.get(index);
        }

        @Override
        public int size() {
            return values.size();
        }
    }
}
