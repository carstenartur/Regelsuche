package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import static de.regelsuche.math.algorithms.iteration.PairReductionPlan.Moment;

/** Ordered row factors for the sum over i &lt; j in each fixed-width window. */
public record RollingPairReductionPlan(List<Term> terms) {
    public RollingPairReductionPlan {
        if (terms.size() > IterationPolynomial.MAX_TERMS) throw new IllegalArgumentException("TERM_LIMIT");
        terms = List.copyOf(terms);
    }

    public record Term(BigInteger coefficient, Moment left, Moment right) {
        public Term {
            Objects.requireNonNull(coefficient);
            Objects.requireNonNull(left);
            Objects.requireNonNull(right);
            if (coefficient.signum() == 0 || coefficient.abs().bitLength() > FiniteIteration.MAX_COEFFICIENT_BITS) {
                throw new IllegalArgumentException("INVALID_PAIR_PLAN_TERM");
            }
        }
    }

    /** The empty monomial is included when the plan needs a running row count. */
    public List<Moment> accumulators() {
        return terms.stream().flatMap(term -> Stream.of(term.left(), term.right())).distinct().sorted().toList();
    }

    public record Verification(Optional<Verified> verified, String reason, long work) {}

    static Verification verify(PairReduction source, RollingPairReductionPlan plan, IterationLimits limits,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(plan);
        var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
        try {
            var original = IterationPolynomial.parsePairSummand(source, work);
            var reconstructed = IterationPolynomial.zero(work);
            var left = new HashMap<Moment, IterationPolynomial>();
            var right = new HashMap<Moment, IterationPolynomial>();
            for (var term : plan.terms()) {
                var a = left.computeIfAbsent(term.left(), moment -> symbolic(moment, source, true, work));
                var b = right.computeIfAbsent(term.right(), moment -> symbolic(moment, source, false, work));
                reconstructed = reconstructed.add(a.multiply(b).scale(ExactRational.integer(term.coefficient())));
            }
            if (!reconstructed.same(original)) throw new Unavailable("PAIR_IDENTITY_MISMATCH");
            return new Verification(Optional.of(new Verified(source, plan)), "VERIFIED", work.used());
        } catch (Unavailable exception) {
            return new Verification(Optional.empty(), exception.getMessage(), work.used());
        }
    }

    private static IterationPolynomial symbolic(Moment moment, PairReduction source, boolean left, Work work) {
        var product = IterationPolynomial.constant(BigInteger.ONE, work);
        for (int index : moment.columns()) {
            if (index >= source.columns().size()) throw new Unavailable("UNKNOWN_COLUMN");
            var column = source.columns().get(index);
            product = product.multiply(IterationPolynomial.variable(left ? column.left() : column.right(), work));
        }
        return product;
    }

    public record Evaluation(List<BigInteger> sums, long rowReads, long work) {
        public Evaluation {
            sums = List.copyOf(sums);
        }
    }

    /** A checked pair identity and its fixed add/remove schedule. */
    public static final class Verified {
        private final PairReduction source;
        private final RollingPairReductionPlan plan;

        private Verified(PairReduction source, RollingPairReductionPlan plan) {
            this.source = source;
            this.plan = plan;
        }

        public PairReduction source() { return source; }
        public RollingPairReductionPlan plan() { return plan; }

        /** Reads each row at entry and at most once at exit through indexed access. */
        public Evaluation evaluate(int rowCount, int width, IntFunction<? extends List<BigInteger>> rowAt,
                IterationLimits limits, BooleanSupplier cancellation) {
            Objects.requireNonNull(rowAt);
            if (rowCount < 0 || width < 1) throw new IllegalArgumentException("INVALID_WINDOW");
            var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
            try {
                work.charge(1);
                if (width > rowCount) return new Evaluation(List.of(), 0, work.used());
                return evaluate(rowCount, width, rowAt, work);
            } catch (Unavailable exception) {
                throw new IllegalArgumentException(exception.getMessage(), exception);
            }
        }

        private Evaluation evaluate(int rowCount, int width, IntFunction<? extends List<BigInteger>> rowAt, Work work) {
            var moments = plan.accumulators();
            var sums = new HashMap<Moment, BigInteger>();
            for (var moment : moments) sums.put(moment, BigInteger.ZERO);
            var outputs = new ArrayList<BigInteger>();
            BigInteger total = BigInteger.ZERO;
            for (int index = 0; index < rowCount; index++) {
                work.charge(1);
                if (index >= width) {
                    var oldest = products(rowAt.apply(index - width), moments, work);
                    // Remove the oldest row from the moments before subtracting its pairs as a left endpoint.
                    adjust(sums, oldest, false, work);
                    total = bounded(total.subtract(pairs(sums, oldest, false, work)), work);
                }
                var newest = products(rowAt.apply(index), moments, work);
                // The newest row participates as a right endpoint against every row already in the window.
                total = bounded(total.add(pairs(sums, newest, true, work)), work);
                adjust(sums, newest, true, work);
                if (index >= width - 1) outputs.add(total);
            }
            return new Evaluation(outputs, 2L * rowCount - width, work.used());
        }

        private Map<Moment, BigInteger> products(List<BigInteger> row, List<Moment> moments, Work work) {
            if (row == null || row.size() != source.columns().size()) throw new Unavailable("ROW_DIMENSION");
            var values = row.toArray(BigInteger[]::new);
            for (var value : values) {
                if (value == null) throw new Unavailable("MISSING_ROW_VALUE");
                work.runtimeInteger(value);
            }
            var products = new HashMap<Moment, BigInteger>();
            for (var moment : moments) {
                BigInteger product = BigInteger.ONE;
                for (int column : moment.columns()) product = bounded(product.multiply(values[column]), work);
                products.put(moment, product);
            }
            return products;
        }

        private static void adjust(Map<Moment, BigInteger> sums, Map<Moment, BigInteger> row, boolean add, Work work) {
            for (var entry : row.entrySet()) {
                var previous = sums.get(entry.getKey());
                var next = add ? previous.add(entry.getValue()) : previous.subtract(entry.getValue());
                sums.put(entry.getKey(), bounded(next, work));
            }
        }

        private BigInteger pairs(Map<Moment, BigInteger> sums, Map<Moment, BigInteger> row, boolean append, Work work) {
            BigInteger total = BigInteger.ZERO;
            for (var term : plan.terms()) {
                var accumulated = sums.get(append ? term.left() : term.right());
                var element = row.get(append ? term.right() : term.left());
                var scaled = bounded(term.coefficient().multiply(accumulated), work);
                var product = bounded(scaled.multiply(element), work);
                total = bounded(total.add(product), work);
            }
            return total;
        }

        private static BigInteger bounded(BigInteger value, Work work) {
            work.runtimeInteger(value);
            return value;
        }
    }
}
