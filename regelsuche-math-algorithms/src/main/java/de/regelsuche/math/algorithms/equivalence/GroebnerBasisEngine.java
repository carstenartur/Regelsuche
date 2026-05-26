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
            return new EngineResult(basis.basis(), Polynomial.zero(), order.name(), basis.steps(), basis.budgetStatus(), true);
        }
        int remainingSteps = Math.max(0, maxSteps - basis.steps());
        PolynomialReducer.ReductionResult reduction = reducer.reduce(polynomial, basis.basis(), order, remainingSteps);
        int totalSteps = basis.steps() + reduction.steps();
        boolean budgetExhausted = reduction.budgetExhausted() || totalSteps > maxSteps;
        String budgetStatus = budgetExhausted ? "BUDGET_EXHAUSTED" : "OK";
        return new EngineResult(
            basis.basis(),
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

    public record EngineResult(
        List<Polynomial> basis,
        Polynomial remainder,
        String monomialOrder,
        int steps,
        String budgetStatus,
        boolean budgetExhausted
    ) {
    }
}
