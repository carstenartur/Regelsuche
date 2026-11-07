package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static de.regelsuche.sdk.optimization.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.sdk.optimization.iteration.IterationPolynomial.Work;
import static de.regelsuche.sdk.optimization.iteration.PairReductionPlan.Moment;
import static de.regelsuche.sdk.optimization.iteration.PairReductionPlan.Term;

/** Derives the accumulators from an input polynomial and leaves authorization to the verifier. */
public final class PairReductionOptimizer {
    public record Result(Optional<PairReductionPlan> plan, String reason, long work) {}

    public Result search(PairReduction source, OptimizationBudget budget, CancellationToken cancellation) {
        Objects.requireNonNull(source);
        var work = new Work(budget, cancellation, PairReduction.MAX_DEGREE);
        try {
            var plan = synthesize(source, work);
            return new Result(Optional.of(plan), "CANDIDATE", work.used);
        } catch (Unavailable exception) {
            return new Result(Optional.empty(), exception.getMessage(), work.used);
        }
    }

    private static PairReductionPlan synthesize(PairReduction source, Work work) {
        var polynomial = source.polynomial(work);
        var leftColumns = new HashMap<SymbolId, Integer>();
        var rightColumns = new HashMap<SymbolId, Integer>();
        for (int index = 0; index < source.columns().size(); index++) {
            var column = source.columns().get(index);
            leftColumns.put(column.left(), index);
            rightColumns.put(column.right(), index);
        }

        var pairs = new LinkedHashMap<Split, BigInteger>();
        for (var term : polynomial.terms.entrySet()) {
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
