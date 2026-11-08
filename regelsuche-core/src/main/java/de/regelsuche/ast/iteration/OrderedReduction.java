package de.regelsuche.ast.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Sum over distinct rows in increasing index order. Each slot binds the same
 * columns, and a restriction relates each pair of adjacent slots.
 */
public record OrderedReduction(List<List<SymbolId>> slots, List<Restriction> restrictions, Expr summand) {
    public static final int MAX_ARITY = 8;

    public OrderedReduction {
        if (slots.size() < 2 || slots.size() > MAX_ARITY || restrictions.size() != slots.size() - 1) {
            throw new IllegalArgumentException("INVALID_ORDERED_ARITY");
        }
        slots = List.copyOf(slots);
        restrictions = List.copyOf(restrictions);
        int columns = slots.getFirst().size();
        if (columns < 1 || columns > PairReduction.MAX_COLUMNS) {
            throw new IllegalArgumentException("COLUMN_LIMIT");
        }
        var symbols = new HashSet<SymbolId>();
        var snapshot = new ArrayList<List<SymbolId>>();
        for (var slot : slots) {
            if (slot.size() != columns) throw new IllegalArgumentException("ROW_DIMENSION");
            var bound = List.copyOf(slot);
            snapshot.add(bound);
            for (var symbol : bound) {
                if (!symbols.add(symbol)) throw new IllegalArgumentException("DUPLICATE_COLUMN_SYMBOL");
            }
        }
        slots = List.copyOf(snapshot);
        for (var restriction : restrictions) {
            if (restriction instanceof KeyInterval interval
                    && (interval.leftColumn() >= columns || interval.rightColumn() >= columns)) {
                throw new IllegalArgumentException("UNKNOWN_COLUMN");
            }
        }
        IterationScope.validate(summand, symbols);
    }

    public int columnCount() {
        return slots.getFirst().size();
    }

    public sealed interface Restriction permits All, KeyInterval {}

    public enum All implements Restriction { ROWS }

    public record Bound(BigInteger offset, boolean inclusive) {
        public Bound {
            Objects.requireNonNull(offset);
            if (offset.abs().bitLength() > FiniteIteration.MAX_COEFFICIENT_BITS) {
                throw new IllegalArgumentException("BOUND_LIMIT");
            }
        }
    }

    /**
     * Constrains the exact difference leftKey - rightKey. Absent bounds
     * are unbounded on that side. Complement selects rows outside the interval.
     */
    public record KeyInterval(int leftColumn, int rightColumn, Optional<Bound> lower, Optional<Bound> upper,
            boolean complement) implements Restriction {
        public KeyInterval {
            Objects.requireNonNull(lower);
            Objects.requireNonNull(upper);
            if (leftColumn < 0 || rightColumn < 0 || (lower.isEmpty() && upper.isEmpty())) {
                throw new IllegalArgumentException("INVALID_KEY_INTERVAL");
            }
            if (lower.isPresent() && upper.isPresent()
                    && lower.orElseThrow().offset().compareTo(upper.orElseThrow().offset()) > 0) {
                throw new IllegalArgumentException("INVALID_KEY_INTERVAL");
            }
        }

        public boolean isPoint() {
            return lower.isPresent() && lower.equals(upper) && lower.orElseThrow().inclusive();
        }
    }
}
