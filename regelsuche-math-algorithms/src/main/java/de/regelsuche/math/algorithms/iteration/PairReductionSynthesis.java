package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import static de.regelsuche.math.algorithms.iteration.PairReductionPlan.Moment;
import static de.regelsuche.math.algorithms.iteration.PairReductionPlan.Term;

/** Derives the accumulators from an input polynomial and leaves authorization to the verifier. */
public final class PairReductionSynthesis {
    public record Result<T>(Optional<T> value, String reason, long work) {}

    /** Bounded sparse form of the summand with its original ordered column bindings. */
    public static final class NormalForm {
        private final List<PairReduction.Column> columns;
        private final Map<IterationPolynomial.Monomial, ExactRational> terms;

        private NormalForm(PairReduction source, IterationPolynomial polynomial) {
            columns = source.columns();
            terms = polynomial.terms();
        }

        List<PairReduction.Column> columns() { return columns; }
        Map<IterationPolynomial.Monomial, ExactRational> terms() { return terms; }

        @Override public boolean equals(Object other) {
            return other instanceof NormalForm form && columns.equals(form.columns) && terms.equals(form.terms);
        }

        @Override public int hashCode() { return Objects.hash(columns, terms); }
    }

    public Result<NormalForm> normalize(PairReduction source, IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(source);
        var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
        try {
            var form = new NormalForm(source, IterationPolynomial.parsePairSummand(source, work));
            return new Result<>(Optional.of(form), "CANDIDATE", work.used());
        } catch (Unavailable exception) {
            return new Result<>(Optional.empty(), exception.getMessage(), work.used());
        }
    }

    public Result<PairReductionPlan> reduce(NormalForm form, IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(form);
        var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
        try {
            work.charge(1);
            if (form.terms().size() > limits.maximumTerms()) throw new Unavailable("TERM_LIMIT");
            var plan = synthesize(form, work);
            return new Result<>(Optional.of(plan), "CANDIDATE", work.used());
        } catch (Unavailable exception) {
            return new Result<>(Optional.empty(), exception.getMessage(), work.used());
        }
    }

    Result<RollingPairReductionPlan> reduceWindows(NormalForm form, IterationLimits limits, BooleanSupplier cancellation) {
        var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
        try {
            work.charge(1);
            if (form.terms().size() > limits.maximumTerms()) throw new Unavailable("TERM_LIMIT");
            var pairs = splitByRow(form, work);
            var terms = new ArrayList<RollingPairReductionPlan.Term>();
            for (var entry : pairs.entrySet()) {
                work.charge(1);
                var coefficient = entry.getValue();
                if (coefficient.abs().bitLength() > FiniteIteration.MAX_COEFFICIENT_BITS) {
                    throw new Unavailable("COEFFICIENT_LIMIT");
                }
                terms.add(new RollingPairReductionPlan.Term(coefficient, entry.getKey().left(), entry.getKey().right()));
            }
            return new Result<>(Optional.of(new RollingPairReductionPlan(terms)), "CANDIDATE", work.used());
        } catch (Unavailable exception) {
            return new Result<>(Optional.empty(), exception.getMessage(), work.used());
        }
    }

    private static Map<Split, BigInteger> splitByRow(NormalForm form, Work work) {
        var leftColumns = new HashMap<SymbolId, Integer>();
        var rightColumns = new HashMap<SymbolId, Integer>();
        for (int index = 0; index < form.columns().size(); index++) {
            work.charge(1);
            var column = form.columns().get(index);
            leftColumns.put(column.left(), index);
            rightColumns.put(column.right(), index);
        }

        var pairs = new LinkedHashMap<Split, BigInteger>();
        for (var term : form.terms().entrySet()) {
            var left = new ArrayList<Integer>();
            var right = new ArrayList<Integer>();
            for (var symbol : term.getKey().symbols()) {
                var column = leftColumns.get(symbol);
                if (column != null) {
                    left.add(column);
                } else {
                    right.add(rightColumns.get(symbol));
                }
            }
            work.charge(1 + left.size() + right.size());
            pairs.put(new Split(new Moment(left), new Moment(right)), term.getValue().numerator());
        }
        return pairs;
    }

    private static PairReductionPlan synthesize(NormalForm form, Work work) {
        var pairs = splitByRow(form, work);
        var numerator = new LinkedHashMap<List<Moment>, BigInteger>();
        for (var entry : pairs.entrySet()) {
            var split = entry.getKey();
            var coefficient = entry.getValue();
            work.charge(1);
            if (!coefficient.equals(pairs.get(new Split(split.right(), split.left())))) {
                throw new Unavailable("ASYMMETRIC_SUMMAND");
            }
            // The rectangular sum separates. Remove its diagonal before dividing by two.
            var diagonal = new ArrayList<>(split.left().columns());
            diagonal.addAll(split.right().columns());
            numerator.merge(split.orderedFactors(), coefficient, BigInteger::add);
            numerator.merge(List.of(new Moment(diagonal)), coefficient.negate(), BigInteger::add);
        }

        work.charge(numerator.size());
        boolean even = numerator.values().stream().noneMatch(coefficient -> coefficient.testBit(0));
        var terms = new ArrayList<Term>();
        for (var entry : numerator.entrySet()) {
            var coefficient = even ? entry.getValue().shiftRight(1) : entry.getValue();
            if (coefficient.signum() != 0) {
                if (coefficient.abs().bitLength() > FiniteIteration.MAX_COEFFICIENT_BITS) {
                    throw new Unavailable("COEFFICIENT_LIMIT");
                }
                terms.add(new Term(coefficient, entry.getKey()));
            }
        }
        return new PairReductionPlan(terms, even ? 1 : 2);
    }

    private record Split(Moment left, Moment right) {
        List<Moment> orderedFactors() {
            return left.compareTo(right) <= 0 ? List.of(left, right) : List.of(right, left);
        }
    }
}
