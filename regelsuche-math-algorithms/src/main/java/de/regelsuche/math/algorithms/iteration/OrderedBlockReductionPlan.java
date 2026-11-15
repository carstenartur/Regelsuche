package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.OrderedReduction;
import de.regelsuche.ast.iteration.OrderedReduction.All;
import de.regelsuche.ast.iteration.PairReduction;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import static de.regelsuche.math.algorithms.iteration.PairReductionPlan.Moment;

/** A candidate set of block statistics for an unrestricted ordered reduction. */
public record OrderedBlockReductionPlan(OrderedReductionPlan reduction, List<List<Moment>> statistics) {
    private static final int MAX_STATISTICS =
        IterationPolynomial.MAX_TERMS * (OrderedReduction.MAX_ARITY * (OrderedReduction.MAX_ARITY + 1) / 2 - 1);

    public OrderedBlockReductionPlan {
        Objects.requireNonNull(reduction);
        if (statistics.size() > MAX_STATISTICS) throw new IllegalArgumentException("BLOCK_STATISTIC_LIMIT");
        var snapshot = new ArrayList<List<Moment>>();
        for (var statistic : statistics) {
            if (statistic.isEmpty() || statistic.size() >= OrderedReduction.MAX_ARITY) {
                throw new IllegalArgumentException("INVALID_BLOCK_STATISTIC");
            }
            snapshot.add(List.copyOf(statistic));
        }
        statistics = List.copyOf(snapshot);
    }

    public record Verification(Optional<Verified> verified, String reason, long work) {}

    record Split(int left, int right) {}
    record WeightedSplit(BigInteger coefficient, Split split) {}
    record Layout(List<List<Split>> statistics, List<WeightedSplit> total) {}

    static void checkSource(OrderedReduction source, Work work) {
        for (var restriction : source.restrictions()) {
            work.charge(1);
            if (restriction != All.ROWS) throw new Unavailable("POINT_UPDATES_REQUIRE_UNRESTRICTED_ROWS");
        }
    }

    static OrderedBlockReductionPlan derive(OrderedReductionPlan reduction, Work work) {
        var required = new LinkedHashSet<List<Moment>>();
        for (var term : reduction.terms()) {
            var factors = term.factors();
            for (int length = 1; length < factors.size(); length++) {
                for (int start = 0; start + length <= factors.size(); start++) {
                    work.charge(1L + length);
                    required.add(List.copyOf(factors.subList(start, start + length)));
                }
            }
        }
        return new OrderedBlockReductionPlan(reduction, List.copyOf(required));
    }

    static OrderedBlockReductionPlan supply(OrderedReductionPlan reduction, List<List<Moment>> statistics, Work work) {
        work.charge(1);
        if (statistics.size() > MAX_STATISTICS) throw new Unavailable("BLOCK_STATISTIC_LIMIT");
        for (var factors : statistics) {
            if (factors.isEmpty() || factors.size() >= OrderedReduction.MAX_ARITY) {
                throw new Unavailable("INVALID_BLOCK_STATISTIC");
            }
            work.charge(1L + factors.size());
        }
        return new OrderedBlockReductionPlan(reduction, statistics);
    }

    static Verified check(OrderedReduction source, OrderedBlockReductionPlan plan, Work work) {
        checkSource(source, work);
        OrderedReductionPlan.check(source, plan.reduction(), false, work);
        var indices = new HashMap<List<Moment>, Integer>();
        for (var factors : plan.statistics()) {
            work.charge(1L + factors.size());
            if (factors.size() >= source.slots().size()) throw new Unavailable("INVALID_BLOCK_STATISTIC");
            int degree = 0;
            for (var moment : factors) {
                for (int column : moment.columns()) {
                    work.charge(1);
                    if (column >= source.columnCount()) throw new Unavailable("UNKNOWN_COLUMN");
                    degree++;
                }
            }
            if (degree > PairReduction.MAX_DEGREE) throw new Unavailable("DEGREE_LIMIT");
            if (indices.putIfAbsent(factors, indices.size()) != null) {
                throw new Unavailable("DUPLICATE_BLOCK_STATISTIC");
            }
        }

        // An increasing tuple across a cut is uniquely a left prefix and a right suffix.
        // Compile only these prescribed products and check that every operand is available.
        var statistics = new ArrayList<List<Split>>();
        for (var factors : plan.statistics()) {
            statistics.add(compileSplits(factors, indices, work));
        }
        var total = new ArrayList<WeightedSplit>();
        for (var term : plan.reduction().terms()) {
            for (var split : compileSplits(term.factors(), indices, work)) {
                total.add(new WeightedSplit(term.coefficient(), split));
            }
        }
        return new Verified(source, plan, new Layout(List.copyOf(statistics), List.copyOf(total)));
    }

    private static List<Split> compileSplits(List<Moment> factors, Map<List<Moment>, Integer> indices, Work work) {
        var splits = new ArrayList<Split>();
        for (int cut = 1; cut < factors.size(); cut++) {
            work.charge(1L + factors.size());
            var left = indices.get(factors.subList(0, cut));
            var right = indices.get(factors.subList(cut, factors.size()));
            if (left == null || right == null) throw new Unavailable("MISSING_BLOCK_STATISTIC");
            splits.add(new Split(left, right));
        }
        return List.copyOf(splits);
    }

    /** Only checked plans can create a mutable execution session. */
    public static final class Verified {
        private final OrderedReduction source;
        private final OrderedBlockReductionPlan plan;
        private final Layout layout;

        private Verified(OrderedReduction source, OrderedBlockReductionPlan plan, Layout layout) {
            this.source = source;
            this.plan = plan;
            this.layout = layout;
        }

        public OrderedReduction source() { return source; }
        public OrderedBlockReductionPlan plan() { return plan; }

        public OrderedBlockAccumulator newAccumulator(List<? extends List<BigInteger>> rows,
                IterationLimits limits, BooleanSupplier cancellation) {
            try {
                return new OrderedBlockAccumulator(source.columnCount(), plan.statistics(), layout, rows,
                    new Work(limits, cancellation, PairReduction.MAX_DEGREE));
            } catch (Unavailable unavailable) {
                throw new IllegalArgumentException(unavailable.getMessage(), unavailable);
            }
        }
    }
}
