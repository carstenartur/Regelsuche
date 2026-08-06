package de.regelsuche.math.algorithms.equivalence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class GroebnerBasisEngine {
    private final MonomialOrder order;
    private final BuchbergerAlgorithm buchberger = new BuchbergerAlgorithm();
    private final PolynomialReducer reducer = new PolynomialReducer();

    public GroebnerBasisEngine(MonomialOrder order) {
        this.order = order;
    }

    public EngineResult normalFormModuloIdeal(
        Polynomial polynomial,
        List<Polynomial> generators,
        int maxSteps,
        int maxPairs
    ) {
        BuchbergerAlgorithm.BasisComputation basis = buchberger.computeBasis(
            generators,
            order,
            maxSteps,
            maxPairs
        );
        if (basis.budgetExhausted()) {
            return result(
                basis,
                List.of(),
                Polynomial.zero(),
                basis.steps(),
                basis.budgetStatus(),
                true,
                "NOT_COMPUTED"
            );
        }

        int remainingSteps = Math.max(0, maxSteps - basis.steps());
        PolynomialReducer.ReductionResult reduction = reducer.reduce(
            polynomial,
            basis.basis(),
            order,
            remainingSteps
        );
        int totalSteps = basis.steps() + reduction.steps();
        boolean budgetExhausted = reduction.budgetExhausted() || totalSteps > maxSteps;
        if (budgetExhausted) {
            return result(
                basis,
                List.of(),
                reduction.remainder(),
                totalSteps,
                "BUDGET_EXHAUSTED",
                true,
                "NOT_COMPUTED"
            );
        }

        InterreductionResult interreduction = reducedBasis(
            basis.basis(),
            Math.max(0, maxSteps - totalSteps)
        );
        return result(
            basis,
            interreduction.basis(),
            reduction.remainder(),
            totalSteps + interreduction.steps(),
            "OK",
            false,
            interreduction.complete() ? "COMPLETE" : "BUDGET_EXHAUSTED"
        );
    }

    public boolean reducesToZeroModuloIdeal(
        Polynomial polynomial,
        List<Polynomial> generators,
        int maxSteps,
        int maxPairs
    ) {
        EngineResult result = normalFormModuloIdeal(polynomial, generators, maxSteps, maxPairs);
        return !result.budgetExhausted() && result.remainder().isZero();
    }

    private EngineResult result(
        BuchbergerAlgorithm.BasisComputation basis,
        List<Polynomial> reducedBasis,
        Polynomial remainder,
        int steps,
        String budgetStatus,
        boolean budgetExhausted,
        String reducedBasisStatus
    ) {
        return new EngineResult(
            basis.basis(),
            reducedBasis,
            remainder,
            order.name(),
            steps,
            budgetStatus,
            budgetExhausted,
            reducedBasisStatus,
            basis.pairsConsidered(),
            basis.pairsReduced(),
            basis.pairsSkippedByProductCriterion(),
            basis.pairsSkippedByChainCriterion(),
            basis.maxPendingPairs()
        );
    }

    private InterreductionResult reducedBasis(List<Polynomial> basis, int maxSteps) {
        List<Polynomial> current = new ArrayList<>(normalizedBasis(basis));
        if (current.size() <= 1) {
            return new InterreductionResult(List.copyOf(current), 0, true);
        }

        int steps = 0;
        int index = 0;
        while (index < current.size()) {
            Polynomial polynomial = current.get(index);
            List<Polynomial> others = new ArrayList<>(current);
            others.remove(index);
            if (!requiresReduction(polynomial, others)) {
                index++;
                continue;
            }

            PolynomialReducer.ReductionResult reduction = reducer.reduce(
                polynomial,
                others,
                order,
                Math.max(0, maxSteps - steps)
            );
            if (reduction.budgetExhausted()) {
                return new InterreductionResult(normalizedBasis(current), steps + reduction.steps(), false);
            }
            steps += reduction.steps();
            Polynomial remainder = reduction.remainder();
            if (remainder.isZero()) {
                current.remove(index);
            } else {
                current.set(index, remainder.monic(order));
            }
            current = new ArrayList<>(normalizedBasis(current));
            index = 0;
        }
        return new InterreductionResult(List.copyOf(current), steps, true);
    }

    private boolean requiresReduction(Polynomial polynomial, List<Polynomial> others) {
        List<Monomial> leadingMonomials = others.stream()
            .filter(other -> !other.isZero())
            .map(other -> other.leadingTerm(order).orElseThrow().monomial())
            .toList();
        for (Monomial monomial : polynomial.terms().keySet()) {
            for (Monomial leadingMonomial : leadingMonomials) {
                if (leadingMonomial.divides(monomial)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Polynomial> normalizedBasis(List<Polynomial> basis) {
        return basis.stream()
            .filter(polynomial -> !polynomial.isZero())
            .map(polynomial -> polynomial.monic(order))
            .distinct()
            .sorted(Comparator
                .comparing(
                    (Polynomial polynomial) -> polynomial.leadingTerm(order)
                        .map(Term::monomial)
                        .orElse(Monomial.constant()),
                    order
                )
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
        boolean budgetExhausted,
        String reducedBasisStatus,
        int pairsConsidered,
        int pairsReduced,
        int pairsSkippedByProductCriterion,
        int pairsSkippedByChainCriterion,
        int maxPendingPairs
    ) {
    }

    private record InterreductionResult(List<Polynomial> basis, int steps, boolean complete) {
    }
}
