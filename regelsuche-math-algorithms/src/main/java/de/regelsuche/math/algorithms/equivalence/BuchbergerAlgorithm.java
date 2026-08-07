package de.regelsuche.math.algorithms.equivalence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

public final class BuchbergerAlgorithm {
    private final PolynomialReducer reducer = new PolynomialReducer();

    public BasisComputation computeBasis(List<Polynomial> generators, MonomialOrder order, int maxSteps, int maxPairs) {
        List<Polynomial> basis = generators.stream()
            .filter(generator -> !generator.isZero())
            .map(generator -> generator.monic(order))
            .distinct()
            .sorted(basisComparator(order))
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        List<Monomial> leadingMonomials = basis.stream()
            .map(polynomial -> polynomial.leadingTerm(order).orElseThrow().monomial())
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        PriorityQueue<CriticalPair> pairs = new PriorityQueue<>(pairComparator(order));
        Set<PairKey> resolvedPairs = new HashSet<>();
        Statistics statistics = new Statistics();

        for (int i = 0; i < basis.size(); i++) {
            for (int j = i + 1; j < basis.size(); j++) {
                if (!enqueuePair(i, j, leadingMonomials, pairs, resolvedPairs, statistics, maxPairs)) {
                    return exhausted(basis, order, 0, statistics);
                }
            }
        }

        int steps = 0;
        while (!pairs.isEmpty()) {
            if (steps >= maxSteps) {
                return exhausted(basis, order, steps, statistics);
            }
            CriticalPair pair = pairs.remove();
            if (chainCriterionApplies(pair, leadingMonomials, resolvedPairs)) {
                statistics.pairsSkippedByChainCriterion++;
                resolvedPairs.add(pair.key());
                continue;
            }

            Polynomial sPolynomial = reducer.sPolynomial(
                basis.get(pair.left()),
                basis.get(pair.right()),
                order
            );
            PolynomialReducer.ReductionResult reduction = reducer.reduce(
                sPolynomial,
                basis,
                order,
                maxSteps - steps
            );
            statistics.pairsReduced++;
            steps += reduction.steps();
            if (reduction.budgetExhausted() || steps > maxSteps) {
                return exhausted(basis, order, steps, statistics);
            }

            resolvedPairs.add(pair.key());
            Polynomial remainder = reduction.remainder();
            if (!remainder.isZero()) {
                Polynomial monicRemainder = remainder.monic(order);
                int nextIndex = basis.size();
                basis.add(monicRemainder);
                leadingMonomials.add(monicRemainder.leadingTerm(order).orElseThrow().monomial());
                for (int i = 0; i < nextIndex; i++) {
                    if (!enqueuePair(
                        i,
                        nextIndex,
                        leadingMonomials,
                        pairs,
                        resolvedPairs,
                        statistics,
                        maxPairs
                    )) {
                        return exhausted(basis, order, steps, statistics);
                    }
                }
            }
        }
        return completed(basis, order, steps, statistics);
    }

    private boolean enqueuePair(
        int left,
        int right,
        List<Monomial> leadingMonomials,
        PriorityQueue<CriticalPair> pairs,
        Set<PairKey> resolvedPairs,
        Statistics statistics,
        int maxPairs
    ) {
        CriticalPair pair = CriticalPair.create(left, right, leadingMonomials);
        statistics.pairsConsidered++;
        if (leadingMonomials.get(pair.left()).isRelativelyPrimeTo(leadingMonomials.get(pair.right()))) {
            statistics.pairsSkippedByProductCriterion++;
            resolvedPairs.add(pair.key());
            return true;
        }
        pairs.add(pair);
        statistics.maxPendingPairs = Math.max(statistics.maxPendingPairs, pairs.size());
        return pairs.size() <= maxPairs;
    }

    private boolean chainCriterionApplies(
        CriticalPair pair,
        List<Monomial> leadingMonomials,
        Set<PairKey> resolvedPairs
    ) {
        for (int middle = 0; middle < leadingMonomials.size(); middle++) {
            if (middle == pair.left() || middle == pair.right()) {
                continue;
            }
            PairKey leftChain = PairKey.create(pair.left(), middle);
            PairKey rightChain = PairKey.create(middle, pair.right());
            if (!resolvedPairs.contains(leftChain) || !resolvedPairs.contains(rightChain)) {
                continue;
            }
            Monomial leftLcm = leadingMonomials.get(pair.left()).lcm(leadingMonomials.get(middle));
            Monomial rightLcm = leadingMonomials.get(middle).lcm(leadingMonomials.get(pair.right()));
            if (leftLcm.divides(pair.lcm()) && rightLcm.divides(pair.lcm())) {
                return true;
            }
        }
        return false;
    }

    private BasisComputation completed(
        List<Polynomial> basis,
        MonomialOrder order,
        int steps,
        Statistics statistics
    ) {
        return new BasisComputation(
            orderedBasis(basis, order),
            steps,
            false,
            "OK",
            statistics.pairsConsidered,
            statistics.pairsReduced,
            statistics.pairsSkippedByProductCriterion,
            statistics.pairsSkippedByChainCriterion,
            statistics.maxPendingPairs
        );
    }

    private BasisComputation exhausted(
        List<Polynomial> basis,
        MonomialOrder order,
        int steps,
        Statistics statistics
    ) {
        return new BasisComputation(
            orderedBasis(basis, order),
            steps,
            true,
            "BUDGET_EXHAUSTED",
            statistics.pairsConsidered,
            statistics.pairsReduced,
            statistics.pairsSkippedByProductCriterion,
            statistics.pairsSkippedByChainCriterion,
            statistics.maxPendingPairs
        );
    }

    private Comparator<CriticalPair> pairComparator(MonomialOrder order) {
        return Comparator
            .comparingInt(CriticalPair::lcmDegree)
            .thenComparing(CriticalPair::lcm, order)
            .thenComparingInt(CriticalPair::left)
            .thenComparingInt(CriticalPair::right);
    }

    private List<Polynomial> orderedBasis(List<Polynomial> basis, MonomialOrder order) {
        return basis.stream().sorted(basisComparator(order)).toList();
    }

    private Comparator<Polynomial> basisComparator(MonomialOrder order) {
        return Comparator
            .comparing(
                (Polynomial polynomial) -> polynomial.leadingTerm(order)
                    .map(Term::monomial)
                    .orElse(Monomial.constant()),
                order
            )
            .thenComparing(polynomial -> polynomial.toCanonicalString(order));
    }

    public record BasisComputation(
        List<Polynomial> basis,
        int steps,
        boolean budgetExhausted,
        String budgetStatus,
        int pairsConsidered,
        int pairsReduced,
        int pairsSkippedByProductCriterion,
        int pairsSkippedByChainCriterion,
        int maxPendingPairs
    ) {
    }

    private static final class Statistics {
        private int pairsConsidered;
        private int pairsReduced;
        private int pairsSkippedByProductCriterion;
        private int pairsSkippedByChainCriterion;
        private int maxPendingPairs;
    }

    private record CriticalPair(int left, int right, Monomial lcm, int lcmDegree) {
        private static CriticalPair create(int first, int second, List<Monomial> leadingMonomials) {
            PairKey key = PairKey.create(first, second);
            Monomial lcm = leadingMonomials.get(key.left()).lcm(leadingMonomials.get(key.right()));
            return new CriticalPair(key.left(), key.right(), lcm, lcm.totalDegree());
        }

        private PairKey key() {
            return new PairKey(left, right);
        }
    }

    private record PairKey(int left, int right) {
        private static PairKey create(int first, int second) {
            return first < second ? new PairKey(first, second) : new PairKey(second, first);
        }
    }
}
