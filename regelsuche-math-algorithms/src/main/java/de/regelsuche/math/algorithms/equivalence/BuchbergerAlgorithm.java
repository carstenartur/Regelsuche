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
        Statistics statistics = new Statistics();
        PreprocessingResult preprocessing = preprocessGenerators(generators, order, maxSteps, statistics);
        if (preprocessing.budgetExhausted()) {
            return exhausted(preprocessing.basis(), order, preprocessing.steps(), statistics);
        }

        List<Polynomial> basis = preprocessing.basis();
        List<Monomial> leadingMonomials = preprocessing.leadingMonomials();
        PriorityQueue<CriticalPair> pairs = new PriorityQueue<>(pairComparator(order));
        Set<PairKey> resolvedPairs = new HashSet<>();

        for (int i = 0; i < basis.size(); i++) {
            for (int j = i + 1; j < basis.size(); j++) {
                if (!enqueuePair(i, j, leadingMonomials, pairs, resolvedPairs, statistics, maxPairs)) {
                    return exhausted(basis, order, preprocessing.steps(), statistics);
                }
            }
        }
        return processPairs(
            basis,
            leadingMonomials,
            pairs,
            resolvedPairs,
            order,
            preprocessing.steps(),
            maxSteps,
            maxPairs,
            statistics
        );
    }

    public BasisComputation extendBasis(
        List<Polynomial> completedBasis,
        List<Polynomial> additionalGenerators,
        MonomialOrder order,
        int maxSteps,
        int maxPairs
    ) {
        List<Polynomial> basis = normalizedPolynomials(completedBasis, order);
        Set<Polynomial> basisSet = new HashSet<>(basis);
        List<Monomial> leadingMonomials = leadingMonomials(basis, order);
        PriorityQueue<CriticalPair> pairs = new PriorityQueue<>(pairComparator(order));
        Set<PairKey> resolvedPairs = new HashSet<>();
        Statistics statistics = new Statistics();
        PolynomialReducer.PreparedBasis preparedBasis = null;
        int preparedBasisSize = -1;

        // Every old-old pair belongs to a basis that was already completed, so it is safe
        // to treat those pairs as resolved for the chain criterion.
        for (int i = 0; i < basis.size(); i++) {
            for (int j = i + 1; j < basis.size(); j++) {
                resolvedPairs.add(PairKey.create(i, j));
            }
        }

        int steps = 0;
        for (Polynomial generator : orderedMonicPolynomials(additionalGenerators, order)) {
            statistics.extensionGeneratorsConsidered++;
            if (basisSet.contains(generator)) {
                statistics.extensionGeneratorsEliminated++;
                continue;
            }

            Polynomial remainder = generator;
            if (requiresReduction(generator, leadingMonomials)) {
                statistics.extensionGeneratorsReduced++;
                if (preparedBasis == null || preparedBasisSize != basis.size()) {
                    preparedBasis = reducer.prepare(basis, order);
                    preparedBasisSize = basis.size();
                }
                PolynomialReducer.ReductionResult reduction = reducer.reduce(
                    generator,
                    preparedBasis,
                    Math.max(0, maxSteps - steps)
                );
                steps += reduction.steps();
                if (reduction.budgetExhausted() || steps > maxSteps) {
                    return exhausted(basis, order, steps, statistics);
                }
                remainder = reduction.remainder();
            }
            if (remainder.isZero()) {
                statistics.extensionGeneratorsEliminated++;
                continue;
            }

            Polynomial monicRemainder = remainder.monic(order);
            if (!basisSet.add(monicRemainder)) {
                statistics.extensionGeneratorsEliminated++;
                continue;
            }
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

        return processPairs(
            basis,
            leadingMonomials,
            pairs,
            resolvedPairs,
            order,
            steps,
            maxSteps,
            maxPairs,
            statistics
        );
    }

    private PreprocessingResult preprocessGenerators(
        List<Polynomial> generators,
        MonomialOrder order,
        int maxSteps,
        Statistics statistics
    ) {
        List<Polynomial> basis = new ArrayList<>();
        Set<Polynomial> basisSet = new HashSet<>();
        List<Monomial> leadingMonomials = new ArrayList<>();
        PolynomialReducer.PreparedBasis preparedBasis = null;
        int preparedBasisSize = -1;
        int steps = 0;

        for (Polynomial generator : orderedMonicPolynomials(generators, order)) {
            statistics.initialGeneratorsConsidered++;
            if (basisSet.contains(generator)) {
                statistics.initialGeneratorsEliminated++;
                continue;
            }

            Polynomial remainder = generator;
            if (requiresReduction(generator, leadingMonomials)) {
                statistics.initialGeneratorsReduced++;
                if (preparedBasis == null || preparedBasisSize != basis.size()) {
                    preparedBasis = reducer.prepare(basis, order);
                    preparedBasisSize = basis.size();
                }
                PolynomialReducer.ReductionResult reduction = reducer.reduce(
                    generator,
                    preparedBasis,
                    Math.max(0, maxSteps - steps)
                );
                steps += reduction.steps();
                if (reduction.budgetExhausted() || steps > maxSteps) {
                    return new PreprocessingResult(
                        basis,
                        leadingMonomials,
                        steps,
                        true
                    );
                }
                remainder = reduction.remainder();
            }
            if (remainder.isZero()) {
                statistics.initialGeneratorsEliminated++;
                continue;
            }

            Polynomial monicRemainder = remainder.monic(order);
            if (!basisSet.add(monicRemainder)) {
                statistics.initialGeneratorsEliminated++;
                continue;
            }
            basis.add(monicRemainder);
            leadingMonomials.add(monicRemainder.leadingTerm(order).orElseThrow().monomial());
        }
        return new PreprocessingResult(basis, leadingMonomials, steps, false);
    }

    private BasisComputation processPairs(
        List<Polynomial> basis,
        List<Monomial> leadingMonomials,
        PriorityQueue<CriticalPair> pairs,
        Set<PairKey> resolvedPairs,
        MonomialOrder order,
        int initialSteps,
        int maxSteps,
        int maxPairs,
        Statistics statistics
    ) {
        int steps = initialSteps;
        PolynomialReducer.PreparedBasis preparedBasis = null;
        int preparedBasisSize = -1;
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
            if (preparedBasis == null || preparedBasisSize != basis.size()) {
                preparedBasis = reducer.prepare(basis, order);
                preparedBasisSize = basis.size();
            }
            PolynomialReducer.ReductionResult reduction = reducer.reduce(
                sPolynomial,
                preparedBasis,
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

    private boolean requiresReduction(Polynomial polynomial, List<Monomial> leadingMonomials) {
        for (Monomial monomial : polynomial.terms().keySet()) {
            for (Monomial leadingMonomial : leadingMonomials) {
                if (leadingMonomial.divides(monomial)) {
                    return true;
                }
            }
        }
        return false;
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
            statistics.maxPendingPairs,
            statistics.initialGeneratorsConsidered,
            statistics.initialGeneratorsReduced,
            statistics.initialGeneratorsEliminated,
            statistics.extensionGeneratorsConsidered,
            statistics.extensionGeneratorsReduced,
            statistics.extensionGeneratorsEliminated
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
            statistics.maxPendingPairs,
            statistics.initialGeneratorsConsidered,
            statistics.initialGeneratorsReduced,
            statistics.initialGeneratorsEliminated,
            statistics.extensionGeneratorsConsidered,
            statistics.extensionGeneratorsReduced,
            statistics.extensionGeneratorsEliminated
        );
    }

    private List<Polynomial> orderedMonicPolynomials(List<Polynomial> polynomials, MonomialOrder order) {
        return polynomials.stream()
            .filter(polynomial -> !polynomial.isZero())
            .map(polynomial -> polynomial.monic(order))
            .sorted(Comparator
                .comparingInt((Polynomial polynomial) -> polynomial.leadingTerm(order)
                    .orElseThrow().monomial().totalDegree())
                .thenComparing(basisComparator(order)))
            .toList();
    }

    private List<Polynomial> normalizedPolynomials(List<Polynomial> polynomials, MonomialOrder order) {
        return orderedMonicPolynomials(polynomials, order).stream()
            .distinct()
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    private List<Monomial> leadingMonomials(List<Polynomial> basis, MonomialOrder order) {
        return basis.stream()
            .map(polynomial -> polynomial.leadingTerm(order).orElseThrow().monomial())
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
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
        int maxPendingPairs,
        int initialGeneratorsConsidered,
        int initialGeneratorsReduced,
        int initialGeneratorsEliminated,
        int extensionGeneratorsConsidered,
        int extensionGeneratorsReduced,
        int extensionGeneratorsEliminated
    ) {
    }

    private static final class Statistics {
        private int pairsConsidered;
        private int pairsReduced;
        private int pairsSkippedByProductCriterion;
        private int pairsSkippedByChainCriterion;
        private int maxPendingPairs;
        private int initialGeneratorsConsidered;
        private int initialGeneratorsReduced;
        private int initialGeneratorsEliminated;
        private int extensionGeneratorsConsidered;
        private int extensionGeneratorsReduced;
        private int extensionGeneratorsEliminated;
    }

    private record PreprocessingResult(
        List<Polynomial> basis,
        List<Monomial> leadingMonomials,
        int steps,
        boolean budgetExhausted
    ) {
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
