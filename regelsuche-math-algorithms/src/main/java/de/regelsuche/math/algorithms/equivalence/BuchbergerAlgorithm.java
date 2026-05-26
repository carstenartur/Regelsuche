package de.regelsuche.math.algorithms.equivalence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BuchbergerAlgorithm {
    private final PolynomialReducer reducer = new PolynomialReducer();

    public BasisComputation computeBasis(List<Polynomial> generators, MonomialOrder order, int maxSteps, int maxPairs) {
        List<Polynomial> basis = new ArrayList<>();
        for (Polynomial generator : generators) {
            if (!generator.isZero()) {
                basis.add(generator.monic(order));
            }
        }
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < basis.size(); i++) {
            for (int j = i + 1; j < basis.size(); j++) {
                pairs.add(new Pair(i, j));
            }
        }
        if (pairs.size() > maxPairs) {
            return new BasisComputation(orderedBasis(basis, order), 0, true, "BUDGET_EXHAUSTED");
        }

        int steps = 0;
        while (!pairs.isEmpty()) {
            if (steps >= maxSteps) {
                return new BasisComputation(orderedBasis(basis, order), steps, true, "BUDGET_EXHAUSTED");
            }
            Pair pair = pairs.remove(0);
            Polynomial sPolynomial = reducer.sPolynomial(basis.get(pair.left()), basis.get(pair.right()), order);
            PolynomialReducer.ReductionResult reduction = reducer.reduce(sPolynomial, basis, order, Math.max(1, maxSteps - steps));
            steps += reduction.steps();
            if (reduction.budgetExhausted() || steps > maxSteps) {
                return new BasisComputation(orderedBasis(basis, order), steps, true, "BUDGET_EXHAUSTED");
            }
            Polynomial remainder = reduction.remainder();
            if (!remainder.isZero()) {
                int nextIndex = basis.size();
                basis.add(remainder.monic(order));
                for (int i = 0; i < nextIndex; i++) {
                    pairs.add(new Pair(i, nextIndex));
                }
                if (pairs.size() > maxPairs) {
                    return new BasisComputation(orderedBasis(basis, order), steps, true, "BUDGET_EXHAUSTED");
                }
            }
        }
        return new BasisComputation(orderedBasis(basis, order), steps, false, "OK");
    }

    private List<Polynomial> orderedBasis(List<Polynomial> basis, MonomialOrder order) {
        return basis.stream().sorted(basisComparator(order)).toList();
    }

    private Comparator<Polynomial> basisComparator(MonomialOrder order) {
        return Comparator
            .comparing((Polynomial polynomial) -> polynomial.leadingTerm(order).map(Term::monomial).orElse(Monomial.constant()), order)
            .thenComparing(polynomial -> polynomial.toCanonicalString(order));
    }

    public record BasisComputation(List<Polynomial> basis, int steps, boolean budgetExhausted, String budgetStatus) {
    }

    private record Pair(int left, int right) {
    }
}
