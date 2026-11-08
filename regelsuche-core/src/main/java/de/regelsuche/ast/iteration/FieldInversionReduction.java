package de.regelsuche.ast.iteration;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * A finite product of terms, or an elementwise output for each selected row.
 * Each term is an exact commutative product of bound values and their inversions.
 * The caller supplies pure bindings and the original row selection.
 */
public record FieldInversionReduction(Kind kind, Inversion inversion, List<Factor> factors) {
    public static final int MAX_FACTORS = 64;

    public FieldInversionReduction {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(inversion);
        if (factors.isEmpty() || factors.size() > MAX_FACTORS) {
            throw new IllegalArgumentException("FIELD_FACTOR_LIMIT");
        }
        factors = List.copyOf(factors);
        var invariants = new HashMap<Integer, Boolean>();
        for (var factor : factors) {
            var previous = invariants.putIfAbsent(factor.binding(), factor.invariant());
            if (previous != null && previous != factor.invariant()) {
                throw new IllegalArgumentException("INCONSISTENT_FACTOR_BINDING");
            }
        }
    }

    public enum Kind { PRODUCT, PER_ELEMENT }

    /** Multiplicative operations in an exact commutative algebra. */
    public sealed interface Inversion permits FixedPower, NonzeroInverse {}

    /** Applies the same positive exponent to each operand, including zero. */
    public record FixedPower(int exponent) implements Inversion {
        public FixedPower {
            if (exponent < 1) throw new IllegalArgumentException("INVALID_FIXED_POWER");
        }
    }

    /** Each operand must be a unit in the commutative multiplicative group. */
    public enum NonzeroInverse implements Inversion { INSTANCE }

    /** Binding numbers name values, while positions distinguish repeated factors. */
    public record Factor(int binding, boolean invariant, boolean inverted) {
        public Factor {
            if (binding < 0) throw new IllegalArgumentException("INVALID_FACTOR_BINDING");
        }
    }
}
