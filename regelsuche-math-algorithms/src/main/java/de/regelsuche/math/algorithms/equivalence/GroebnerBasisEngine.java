package de.regelsuche.math.algorithms.equivalence;

import java.util.List;

public final class GroebnerBasisEngine {
    private final MonomialOrder order;
    private final BuchbergerAlgorithm buchberger = new BuchbergerAlgorithm();
    private final PolynomialReducer reducer = new PolynomialReducer();

    public GroebnerBasisEngine(MonomialOrder order) {
        this.order = order;
    }

    public EngineResult normalFormModuloIdeal(Polynomial polynomial, List<Polynomial> generators, int maxSteps, int maxPairs) {
        BuchbergerAlgorithm.BasisComputation basis = buchberger.computeBasis(generators, order, maxSteps, maxPairs);
        if (basis.budgetExhausted()) {
            return new EngineResult(
                basis.basis(),
                reducedBasis(basis.basis(), Math.max(0, maxSteps - basis.steps())),
                Polynomial.zero(),
                order.name(),
                basis.steps(),
                basis.budgetStatus(),
                true
            );
        }
        int remainingSteps = Math.max(0, maxSteps - basis.steps());
        PolynomialReducer.ReductionResult reduction = reducer.reduce(polynomial, basis.basis(), order, remainingSteps);
        int totalSteps = basis.steps() + reduction.steps();
        boolean budgetExhausted = reduction.budgetExhausted() || totalSteps > maxSteps;
        String budgetStatus = budgetExhausted ? "BUDGET_EXHAUSTED" : "OK";
        List<Polynomial> reducedBasis = reducedBasis(basis.basis(), Math.max(0, maxSteps - totalSteps));
        return new EngineResult(
            basis.basis(),
            reducedBasis,
            reduction.remainder(),
            order.name(),
            totalSteps,
            budgetStatus,
            budgetExhausted
        );
    }

    public boolean reducesToZeroModuloIdeal(Polynomial polynomial, List<Polynomial> generators, int maxSteps, int maxPairs) {
        EngineResult result = normalFormModuloIdeal(polynomial, generators, maxSteps, maxPairs);
        return !result.budgetExhausted() && result.remainder().isZero();
    }

    private List<Polynomial> reducedBasis(List<Polynomial> basis, int maxSteps) {
        if (basis == null || basis.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<Polynomial> reduced = new java.util.ArrayList<>();
        int remainingSteps = Math.max(0, maxSteps);
        for (int i = 0; i < basis.size(); i++) {
            java.util.ArrayList<Polynomial> others = new java.util.ArrayList<>(basis);
            Polynomial current = others.remove(i);
            PolynomialReducer.ReductionResult reduction = reducer.reduce(current, others, order, remainingSteps);
            remainingSteps = Math.max(0, remainingSteps - reduction.steps());
            Polynomial remainder = reduction.remainder();
            if (!remainder.isZero()) {
                reduced.add(remainder.monic(order));
            }
            if (reduction.budgetExhausted()) {
                break;
            }
        }
        return reduced.stream()
            .distinct()
            .sorted(java.util.Comparator
                .comparing((Polynomial polynomial) -> polynomial.leadingTerm(order).map(Term::monomial).orElse(Monomial.constant()), order)
                .thenComparing(polynomial -> polynomial.toCanonicalString(order)))
            .toList();
    }

    public record EngineResult(
        List<Polynomial> basis,
        List<Polynomial> reducedBasis,
        Polynomial remainder,
        String monomialOrder,
        int steps,
        String budgetStatus,
        boolean budgetExhausted
    ) {
    }
}
