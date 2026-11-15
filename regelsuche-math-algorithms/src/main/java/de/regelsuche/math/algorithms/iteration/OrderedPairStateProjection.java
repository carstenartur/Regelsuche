package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.FiniteIteration;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import static de.regelsuche.math.algorithms.iteration.OrderedBlockReductionPlan.Split;
import static de.regelsuche.math.algorithms.iteration.OrderedBlockReductionPlan.WeightedSplit;
import static de.regelsuche.math.algorithms.iteration.OrderedReductionPlan.Term;
import static de.regelsuche.math.algorithms.iteration.PairReductionPlan.Moment;

/** A candidate factorization of a triple summand into an adjacent pair and a row polynomial. */
public record OrderedPairStateProjection(boolean pairFirst, List<Term> pairTerms, List<RowTerm> rowTerms) {
    public OrderedPairStateProjection {
        if (pairTerms.size() < 2 || rowTerms.isEmpty()
                || (long) pairTerms.size() * rowTerms.size() > IterationPolynomial.MAX_TERMS) {
            throw new IllegalArgumentException("INVALID_PAIR_STATE_PROJECTION");
        }
        pairTerms = List.copyOf(pairTerms);
        rowTerms = List.copyOf(rowTerms);
        if (pairTerms.stream().anyMatch(term -> term.factors().size() != 2)) {
            throw new IllegalArgumentException("PAIR_STATE_REQUIRES_TWO_SLOTS");
        }
    }

    public record RowTerm(Moment factor, BigInteger coefficient) {
        public RowTerm {
            Objects.requireNonNull(factor);
            if (coefficient.signum() == 0 || coefficient.abs().bitLength() > FiniteIteration.MAX_COEFFICIENT_BITS) {
                throw new IllegalArgumentException("INVALID_ROW_COEFFICIENT");
            }
        }
    }

    record Kernel(List<Term> terms, Map<List<Moment>, BigInteger> coefficients, List<List<Moment>> statistics) {}
    record Match(OrderedPairStateProjection projection, List<List<Moment>> statistics) {}
    record Compiled(List<WeightedSplit> pair, List<WeightedSplit> total) {}
    private record Scale(BigInteger coefficient, int count) {}

    static Optional<Kernel> kernel(List<Term> terms, Work work) {
        if (terms.size() < 2) return Optional.empty();
        var canonical = new TreeMap<List<Moment>, BigInteger>(
            Comparator.<List<Moment>, Moment>comparing(List::getFirst).thenComparing(List::getLast));
        var statistics = new LinkedHashSet<List<Moment>>();
        BigInteger divisor = BigInteger.ZERO;
        for (var term : terms) {
            work.charge(1L + term.factors().size());
            if (term.factors().size() != 2 || canonical.putIfAbsent(term.factors(), term.coefficient()) != null) {
                return Optional.empty();
            }
            divisor = divisor.gcd(term.coefficient());
            for (var moment : term.factors()) statistics.add(List.of(moment));
        }
        if (canonical.firstEntry().getValue().signum() < 0) divisor = divisor.negate();
        var primitive = new LinkedHashMap<List<Moment>, BigInteger>();
        var pair = new ArrayList<Term>();
        for (var entry : canonical.entrySet()) {
            work.charge(1);
            var coefficient = entry.getValue().divide(divisor);
            primitive.put(entry.getKey(), coefficient);
            pair.add(new Term(coefficient, entry.getKey()));
        }
        return Optional.of(new Kernel(List.copyOf(pair), Collections.unmodifiableMap(primitive), List.copyOf(statistics)));
    }

    static Optional<Match> reuse(OrderedReductionPlan reduction, Kernel kernel, Work work) {
        work.charge(1);
        if (reduction.lookups().size() != 2 || reduction.terms().size() % kernel.terms().size() != 0) {
            return Optional.empty();
        }
        return fit(reduction, kernel, true, work).or(() -> fit(reduction, kernel, false, work));
    }

    static Optional<Match> infer(OrderedReductionPlan reduction, Work work) {
        work.charge(1);
        if (reduction.lookups().size() != 2 || reduction.terms().size() < 2) return Optional.empty();
        return infer(reduction, true, work).or(() -> infer(reduction, false, work));
    }

    private static Optional<Match> infer(OrderedReductionPlan reduction, boolean pairFirst, Work work) {
        int outer = pairFirst ? 2 : 0;
        var first = reduction.terms().getFirst().factors().get(outer);
        var slice = new ArrayList<Term>();
        for (var term : reduction.terms()) {
            work.charge(1);
            if (term.factors().get(outer).equals(first)) {
                slice.add(new Term(term.coefficient(), pairOf(term, pairFirst)));
            }
        }
        return kernel(slice, work).flatMap(kernel -> fit(reduction, kernel, pairFirst, work));
    }

    private static Optional<Match> fit(OrderedReductionPlan reduction, Kernel kernel, boolean pairFirst, Work work) {
        var scales = new LinkedHashMap<Moment, Scale>();
        for (var term : reduction.terms()) {
            work.charge(1L + term.factors().size());
            var coefficient = kernel.coefficients().get(pairOf(term, pairFirst));
            if (coefficient == null) return Optional.empty();
            var outer = term.factors().get(pairFirst ? 2 : 0);
            var scale = scales.get(outer);
            if (scale == null) {
                var ratio = term.coefficient().divideAndRemainder(coefficient);
                if (ratio[1].signum() != 0) return Optional.empty();
                scales.put(outer, new Scale(ratio[0], 1));
            } else {
                if (!scale.coefficient().multiply(coefficient).equals(term.coefficient())) return Optional.empty();
                scales.put(outer, new Scale(scale.coefficient(), scale.count() + 1));
            }
        }
        var row = new ArrayList<RowTerm>();
        for (var entry : scales.entrySet()) {
            work.charge(1);
            if (entry.getValue().count() != kernel.terms().size()) return Optional.empty();
            row.add(new RowTerm(entry.getKey(), entry.getValue().coefficient()));
        }
        if (row.isEmpty()) return Optional.empty();
        var projection = new OrderedPairStateProjection(pairFirst, kernel.terms(), row);
        return projection.smallerStatistics(kernel.statistics(), work)
            .map(statistics -> new Match(projection, statistics));
    }

    private static List<Moment> pairOf(Term term, boolean pairFirst) {
        return pairFirst ? term.factors().subList(0, 2) : term.factors().subList(1, 3);
    }

    private Optional<List<List<Moment>>> smallerStatistics(List<List<Moment>> pairStatistics, Work work) {
        work.charge(pairStatistics.size());
        var required = new LinkedHashSet<>(pairStatistics);
        for (var row : rowTerms) {
            work.charge(2);
            required.add(List.of(row.factor()));
            for (var pair : pairTerms) {
                work.charge(3);
                required.add(otherPair(pair, row.factor()));
            }
        }
        // One new aggregate replaces the pair words that are no longer needed by the other cut.
        int replaced = 0;
        for (var pair : pairTerms) {
            work.charge(3);
            if (!required.contains(pair.factors()) && ++replaced == 2) {
                return Optional.of(List.copyOf(required));
            }
        }
        return Optional.empty();
    }

    Compiled checkAndCompile(OrderedReductionPlan reduction, Map<List<Moment>, Integer> indices, Work work) {
        work.charge(1);
        if (reduction.lookups().size() != 2 || reduction.terms().size() != pairTerms.size() * rowTerms.size()) {
            throw new Unavailable("PAIR_STATE_IDENTITY_MISMATCH");
        }
        var original = new HashMap<List<Moment>, BigInteger>();
        for (var term : reduction.terms()) {
            work.charge(1L + term.factors().size());
            if (original.putIfAbsent(term.factors(), term.coefficient()) != null) {
                throw new Unavailable("PAIR_STATE_IDENTITY_MISMATCH");
            }
        }
        var pairProgram = new ArrayList<WeightedSplit>();
        for (var pair : pairTerms) {
            int left = index(indices, List.of(pair.factors().getFirst()), work);
            int right = index(indices, List.of(pair.factors().getLast()), work);
            pairProgram.add(new WeightedSplit(pair.coefficient(), new Split(left, right)));
        }
        int pairIndex = indices.size();
        var totalProgram = new ArrayList<WeightedSplit>();
        for (var row : rowTerms) {
            int outer = index(indices, List.of(row.factor()), work);
            var sharedSplit = pairFirst ? new Split(pairIndex, outer) : new Split(outer, pairIndex);
            totalProgram.add(new WeightedSplit(row.coefficient(), sharedSplit));
            for (var pair : pairTerms) {
                // Reconstruct the entire source product, independently of the matching procedure.
                work.charge(3);
                var factors = pairFirst
                    ? List.of(pair.factors().getFirst(), pair.factors().getLast(), row.factor())
                    : List.of(row.factor(), pair.factors().getFirst(), pair.factors().getLast());
                var coefficient = pair.coefficient().multiply(row.coefficient());
                if (!coefficient.equals(original.remove(factors))) throw new Unavailable("PAIR_STATE_IDENTITY_MISMATCH");
                int mixed = index(indices, otherPair(pair, row.factor()), work);
                var endpoint = pairFirst ? pair.factors().getFirst() : pair.factors().getLast();
                int single = index(indices, List.of(endpoint), work);
                var split = pairFirst ? new Split(single, mixed) : new Split(mixed, single);
                totalProgram.add(new WeightedSplit(coefficient, split));
            }
        }
        return new Compiled(List.copyOf(pairProgram), List.copyOf(totalProgram));
    }

    private List<Moment> otherPair(Term pair, Moment outer) {
        return pairFirst ? List.of(pair.factors().getLast(), outer) : List.of(outer, pair.factors().getFirst());
    }

    private static int index(Map<List<Moment>, Integer> indices, List<Moment> factors, Work work) {
        work.charge(1L + factors.size());
        var index = indices.get(factors);
        if (index == null) throw new Unavailable("MISSING_BLOCK_STATISTIC");
        return index;
    }
}
