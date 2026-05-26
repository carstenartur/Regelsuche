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
        PolynomialReducer.ReductionResult reduction = reducer.reduce(polynomial, basis.basis(), order, maxSteps);
        boolean budgetExhausted = reduction.budgetExhausted();
        String budgetStatus = budgetExhausted ? "BUDGET_EXHAUSTED" : "OK";
        return new EngineResult(
            basis.basis(),
            reduction.remainder(),
            order.name(),
            basis.steps() + reduction.steps(),
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
