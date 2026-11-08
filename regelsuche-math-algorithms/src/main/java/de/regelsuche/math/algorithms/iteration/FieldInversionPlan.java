package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.FieldInversionReduction;
import de.regelsuche.ast.iteration.FieldInversionReduction.Kind;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * Factor positions for a checked inversion schedule. Product plans combine the
 * grouped factors over the selected rows before inversion. Elementwise plans
 * combine invariant factors once and apply the result to each selected row.
 */
public record FieldInversionPlan(Kind kind, List<Integer> ordinaryFactors, List<Integer> groupedInverses,
        List<Integer> rowInverses, boolean requiresNonempty) {
    public FieldInversionPlan {
        Objects.requireNonNull(kind);
        long total = (long) ordinaryFactors.size() + groupedInverses.size() + rowInverses.size();
        if (total > FieldInversionReduction.MAX_FACTORS) {
            throw new IllegalArgumentException("FIELD_FACTOR_LIMIT");
        }
        ordinaryFactors = List.copyOf(ordinaryFactors);
        groupedInverses = List.copyOf(groupedInverses);
        rowInverses = List.copyOf(rowInverses);
    }

    static Optional<FieldInversionPlan> derive(FieldInversionReduction source, LongConsumer charge) {
        var ordinary = new ArrayList<Integer>();
        var grouped = new ArrayList<Integer>();
        var perRow = new ArrayList<Integer>();
        for (int i = 0; i < source.factors().size(); i++) {
            charge.accept(1);
            var factor = source.factors().get(i);
            if (!factor.inverted()) ordinary.add(i);
            else if (source.kind() == Kind.PRODUCT || factor.invariant()) grouped.add(i);
            else perRow.add(i);
        }
        if (grouped.isEmpty()) return Optional.empty();
        return Optional.of(new FieldInversionPlan(source.kind(), ordinary, grouped, perRow, true));
    }

    /**
     * Replay the factor partition against the source. In a commutative monoid,
     * a fixed positive power preserves products, even with a zero factor.
     * Inversion has the same law on a commutative group of units.
     */
    static Optional<Verified> check(FieldInversionReduction source, FieldInversionPlan candidate, LongConsumer charge) {
        charge.accept(1);
        if (candidate.kind() != source.kind() || !candidate.requiresNonempty() || candidate.groupedInverses().isEmpty()) {
            return Optional.empty();
        }
        var seen = new boolean[source.factors().size()];
        if (!checkPositions(source, candidate.ordinaryFactors(), seen, Role.ORDINARY, charge)
                || !checkPositions(source, candidate.groupedInverses(), seen, Role.GROUPED, charge)
                || !checkPositions(source, candidate.rowInverses(), seen, Role.ROW, charge)) {
            return Optional.empty();
        }
        for (boolean factorSeen : seen) {
            charge.accept(1);
            if (!factorSeen) return Optional.empty();
        }
        return Optional.of(new Verified(source, candidate));
    }

    private enum Role { ORDINARY, GROUPED, ROW }

    private static boolean checkPositions(FieldInversionReduction source, List<Integer> positions, boolean[] seen,
            Role role, LongConsumer charge) {
        for (int position : positions) {
            charge.accept(1);
            if (position < 0 || position >= seen.length || seen[position]) return false;
            var factor = source.factors().get(position);
            boolean admitted = switch (role) {
                case ORDINARY -> !factor.inverted();
                case GROUPED -> factor.inverted() && (source.kind() == Kind.PRODUCT || factor.invariant());
                case ROW -> factor.inverted() && source.kind() == Kind.PER_ELEMENT && !factor.invariant();
            };
            if (!admitted) return false;
            seen[position] = true;
        }
        return true;
    }

    public record Cost(BigInteger originalInversions, BigInteger plannedInversions,
            BigInteger originalMultiplications, BigInteger plannedMultiplications) {}

    /** A plan replayed for the exact source and inversion contract. */
    public static final class Verified {
        private final FieldInversionReduction source;
        private final FieldInversionPlan plan;

        private Verified(FieldInversionReduction source, FieldInversionPlan plan) {
            this.source = source;
            this.plan = plan;
        }

        public FieldInversionReduction source() { return source; }
        public FieldInversionPlan plan() { return plan; }

        /** Multiplication counts exclude the internal work of the inversion primitive. */
        public Cost cost(long selectedRows) {
            if (selectedRows < 0) throw new IllegalArgumentException("NEGATIVE_ROW_COUNT");
            if (selectedRows == 0) {
                return new Cost(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
            }
            var rows = BigInteger.valueOf(selectedRows);
            int grouped = plan.groupedInverses().size();
            int rowInverses = plan.rowInverses().size();
            int factors = source.factors().size();
            var originalInversions = rows.multiply(BigInteger.valueOf(grouped + rowInverses));
            var plannedInversions = BigInteger.ONE.add(rows.multiply(BigInteger.valueOf(rowInverses)));
            if (source.kind() == Kind.PRODUCT) {
                var products = rows.multiply(BigInteger.valueOf(factors));
                var combine = plan.ordinaryFactors().isEmpty() ? BigInteger.ZERO : BigInteger.ONE;
                return new Cost(originalInversions, plannedInversions, products, products.add(combine));
            }
            var originalProducts = rows.multiply(BigInteger.valueOf(factors - 1L));
            var plannedProducts = rows.multiply(BigInteger.valueOf(factors - grouped))
                .add(BigInteger.valueOf(grouped - 1L));
            return new Cost(originalInversions, plannedInversions, originalProducts, plannedProducts);
        }
    }
}
