package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import static de.regelsuche.math.algorithms.iteration.PairReductionPlan.Moment;

/** Proves the proposed program for all finite lists of integer rows. */
public final class PairReductionVerifier {
    public record Result(Optional<Verified> verified, String reason, long work) {}
    public record NormalizationCheck(boolean accepted, String reason, long work) {}

    public NormalizationCheck verifyNormalization(PairReduction source, PairReductionSynthesis.NormalForm form,
            IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(form);
        var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
        try {
            work.charge(source.columns().size());
            if (!source.columns().equals(form.columns())) throw new Unavailable("COLUMN_BINDING_MISMATCH");
            var polynomial = IterationPolynomial.parsePairSummand(source, work);
            work.charge(polynomial.terms().size() + form.terms().size());
            if (!polynomial.terms().equals(form.terms())) throw new Unavailable("SUMMAND_MISMATCH");
            return new NormalizationCheck(true, "VERIFIED_NORMALIZATION", work.used());
        } catch (Unavailable exception) {
            return new NormalizationCheck(false, exception.getMessage(), work.used());
        }
    }

    public Result verify(PairReduction source, PairReductionPlan plan, IterationLimits limits,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(plan);
        var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
        try {
            var polynomial = IterationPolynomial.parsePairSummand(source, work);
            var symbolic = new HashMap<Moment, SymbolicMoment>();
            var oneRow = IterationPolynomial.zero(work);
            var twoRows = IterationPolynomial.zero(work);
            for (var term : plan.terms()) {
                var first = symbolic.computeIfAbsent(term.factors().getFirst(),
                    moment -> symbolicMoment(source, moment, work));
                var one = first.oneRow();
                var two = first.twoRows();
                if (term.factors().size() == 2) {
                    var second = symbolic.computeIfAbsent(term.factors().getLast(),
                        moment -> symbolicMoment(source, moment, work));
                    one = one.multiply(second.oneRow());
                    two = two.multiply(second.twoRows());
                }
                var coefficient = ExactRational.integer(term.coefficient());
                oneRow = oneRow.add(one.scale(coefficient));
                twoRows = twoRows.add(two.scale(coefficient));
            }

            // Degree <= 2 in sums means that the expansion contains only
            // single-row and two-distinct-row contributions. Check their
            // entire polynomials, with independent symbols for both rows.
            if (!oneRow.same(IterationPolynomial.zero(work))) {
                throw new Unavailable("DIAGONAL_MISMATCH");
            }
            if (!twoRows.same(polynomial.scale(ExactRational.integer(plan.divisor())))) {
                throw new Unavailable("PAIR_IDENTITY_MISMATCH");
            }
            return new Result(Optional.of(new Verified(source, plan, polynomial.expression())), "VERIFIED", work.used());
        } catch (Unavailable exception) {
            return new Result(Optional.empty(), exception.getMessage(), work.used());
        }
    }

    private static SymbolicMoment symbolicMoment(PairReduction source, Moment moment, Work work) {
        var left = IterationPolynomial.constant(BigInteger.ONE, work);
        var right = IterationPolynomial.constant(BigInteger.ONE, work);
        for (int index : moment.columns()) {
            if (index >= source.columns().size()) {
                throw new Unavailable("UNKNOWN_COLUMN");
            }
            var column = source.columns().get(index);
            left = left.multiply(IterationPolynomial.variable(column.left(), work));
            right = right.multiply(IterationPolynomial.variable(column.right(), work));
        }
        return new SymbolicMoment(left, left.add(right));
    }

    private record SymbolicMoment(IterationPolynomial oneRow, IterationPolynomial twoRows) {}

    /** A checked program may be run on multiple inputs without repeating its symbolic proof. */
    public static final class Verified {
        private final PairReduction source;
        private final PairReductionPlan plan;
        private final Expr expandedSummand;
        private final List<Moment> accumulators;

        private Verified(PairReduction source, PairReductionPlan plan, Expr expandedSummand) {
            this.source = source;
            this.plan = plan;
            this.expandedSummand = expandedSummand;
            this.accumulators = plan.accumulators();
        }

        public PairReduction source() { return source; }
        public PairReductionPlan plan() { return plan; }
        public Expr expandedSummand() { return expandedSummand; }

        public Evaluation evaluate(Iterable<? extends List<BigInteger>> rows, IterationLimits limits,
                BooleanSupplier cancellation) {
            Objects.requireNonNull(rows);
            var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
            try {
                return evaluate(rows, work);
            } catch (Unavailable exception) {
                throw new IllegalArgumentException(exception.getMessage(), exception);
            }
        }

        private Evaluation evaluate(Iterable<? extends List<BigInteger>> rows, Work work) {
            work.charge(1);
            var sums = new BigInteger[accumulators.size()];
            Arrays.fill(sums, BigInteger.ZERO);
            var values = new BigInteger[source.columns().size()];
            long count = 0;
            for (var row : rows) {
                work.charge(1);
                if (row == null || row.size() != values.length) {
                    throw new Unavailable("ROW_DIMENSION");
                }
                for (int i = 0; i < values.length; i++) {
                    values[i] = row.get(i);
                    if (values[i] == null) {
                        throw new Unavailable("MISSING_ROW_VALUE");
                    }
                    work.runtimeInteger(values[i]);
                }
                for (int i = 0; i < sums.length; i++) {
                    var columns = accumulators.get(i).columns();
                    BigInteger product = values[columns.getFirst()];
                    for (int j = 1; j < columns.size(); j++) {
                        product = bounded(product.multiply(values[columns.get(j)]), work);
                    }
                    sums[i] = bounded(sums[i].add(product), work);
                }
                count++;
            }
            work.charge(0);

            var moments = new HashMap<Moment, BigInteger>();
            moments.put(new Moment(List.of()), BigInteger.valueOf(count));
            for (int i = 0; i < sums.length; i++) {
                moments.put(accumulators.get(i), sums[i]);
            }
            BigInteger numerator = BigInteger.ZERO;
            for (var term : plan.terms()) {
                BigInteger product = term.coefficient();
                for (var moment : term.factors()) {
                    product = bounded(product.multiply(moments.get(moment)), work);
                }
                numerator = bounded(numerator.add(product), work);
            }
            var result = numerator.divideAndRemainder(BigInteger.valueOf(plan.divisor()));
            if (result[1].signum() != 0) {
                throw new IllegalStateException("verified pair plan produced a nonintegral result");
            }
            return new Evaluation(result[0], count, work.used());
        }

        private static BigInteger bounded(BigInteger value, Work work) {
            work.runtimeInteger(value);
            return value;
        }
    }

    public record Evaluation(BigInteger value, long rows, long work) {}
}
