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

    public BasisPreparation prepareIdeal(List<Polynomial> generators, int maxSteps, int maxPairs) {
        return preparationFrom(
            buchberger.computeBasis(generators, order, maxSteps, maxPairs),
            0,
            0
        );
    }

    public BasisPreparation extendIdeal(
        BasisPreparation completedPreparation,
        List<Polynomial> additionalGenerators,
        int maxSteps,
        int maxPairs
    ) {
        if (completedPreparation.budgetExhausted()) {
            throw new IllegalArgumentException("only completed Gröbner bases can be extended incrementally");
        }
        return preparationFrom(
            buchberger.extendBasis(
                completedPreparation.basis(),
                additionalGenerators,
                order,
                maxSteps,
                maxPairs
            ),
            completedPreparation.basis().size(),
            completedPreparation.reusablePreparationSteps()
        );
    }

    private BasisPreparation preparationFrom(
        BuchbergerAlgorithm.BasisComputation basis,
        int incrementalBaseSize,
        int reusableBaseSteps
    ) {
        PolynomialReducer.PreparedBasis preparedReducers = basis.budgetExhausted()
            ? null
            : reducer.prepare(basis.basis(), order);
        return new BasisPreparation(
            basis.basis(),
            preparedReducers,
            new ReducedBasisMemo(),
            basis.steps(),
            reusableBaseSteps + basis.steps(),
            basis.budgetStatus(),
            basis.budgetExhausted(),
            basis.pairsConsidered(),
            basis.pairsReduced(),
            basis.pairsSkippedByProductCriterion(),
            basis.pairsSkippedByChainCriterion(),
            basis.maxPendingPairs(),
            basis.initialGeneratorsConsidered(),
            basis.initialGeneratorsReduced(),
            basis.initialGeneratorsEliminated(),
            incrementalBaseSize,
            basis.extensionGeneratorsConsidered(),
            basis.extensionGeneratorsReduced(),
            basis.extensionGeneratorsEliminated()
        );
    }

    public EngineResult normalFormModuloIdeal(
        Polynomial polynomial,
        List<Polynomial> generators,
        int maxSteps,
        int maxPairs
    ) {
        BasisPreparation preparation = prepareIdeal(generators, maxSteps, maxPairs);
        return normalFormModuloPreparedIdeal(polynomial, preparation, maxSteps, false);
    }

    public EngineResult normalFormModuloPreparedIdeal(
        Polynomial polynomial,
        BasisPreparation preparation,
        int maxSteps,
        boolean basisCacheHit
    ) {
        int basisPreparationSteps = basisCacheHit ? 0 : preparation.steps();
        int basisPreparationStepsSaved = basisCacheHit
            ? preparation.reusablePreparationSteps()
            : Math.max(0, preparation.reusablePreparationSteps() - preparation.steps());
        if (preparation.budgetExhausted()) {
            return result(
                preparation,
                List.of(),
                Polynomial.zero(),
                basisPreparationSteps,
                0,
                0,
                preparation.budgetStatus(),
                true,
                "NOT_COMPUTED",
                basisCacheHit,
                basisPreparationStepsSaved,
                false,
                0
            );
        }

        int remainingSteps = Math.max(0, maxSteps - basisPreparationSteps);
        PolynomialReducer.ReductionResult reduction = reducer.reduce(
            polynomial,
            preparation.preparedReducers(),
            remainingSteps
        );
        int proofSteps = basisPreparationSteps + reduction.steps();
        boolean budgetExhausted = reduction.budgetExhausted() || proofSteps > maxSteps;
        if (budgetExhausted) {
            return result(
                preparation,
                List.of(),
                reduction.remainder(),
                basisPreparationSteps,
                reduction.steps(),
                0,
                "BUDGET_EXHAUSTED",
                true,
                "NOT_COMPUTED",
                basisCacheHit,
                basisPreparationStepsSaved,
                false,
                0
            );
        }

        InterreductionReuse interreduction = reducedBasis(
            preparation,
            Math.max(0, maxSteps - proofSteps)
        );
        return result(
            preparation,
            interreduction.result().basis(),
            reduction.remainder(),
            basisPreparationSteps,
            reduction.steps(),
            interreduction.cacheHit() ? 0 : interreduction.result().steps(),
            "OK",
            false,
            interreduction.result().complete() ? "COMPLETE" : "BUDGET_EXHAUSTED",
            basisCacheHit,
            basisPreparationStepsSaved,
            interreduction.cacheHit(),
            interreduction.stepsSaved()
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
        BasisPreparation preparation,
        List<Polynomial> reducedBasis,
        Polynomial remainder,
        int basisPreparationSteps,
        int queryReductionSteps,
        int interreductionSteps,
        String budgetStatus,
        boolean budgetExhausted,
        String reducedBasisStatus,
        boolean basisCacheHit,
        int basisPreparationStepsSaved,
        boolean reducedBasisCacheHit,
        int reducedBasisStepsSaved
    ) {
        return new EngineResult(
            preparation.basis(),
            reducedBasis,
            remainder,
            order.name(),
            basisPreparationSteps + queryReductionSteps + interreductionSteps,
            budgetStatus,
            budgetExhausted,
            reducedBasisStatus,
            preparation.pairsConsidered(),
            preparation.pairsReduced(),
            preparation.pairsSkippedByProductCriterion(),
            preparation.pairsSkippedByChainCriterion(),
            preparation.maxPendingPairs(),
            basisCacheHit,
            basisPreparationSteps,
            basisPreparationStepsSaved,
            queryReductionSteps,
            interreductionSteps,
            reducedBasisCacheHit,
            reducedBasisStepsSaved,
            preparation.initialGeneratorsConsidered(),
            preparation.initialGeneratorsReduced(),
            preparation.initialGeneratorsEliminated(),
            preparation.incrementalBaseSize(),
            preparation.extensionGeneratorsConsidered(),
            preparation.extensionGeneratorsReduced(),
            preparation.extensionGeneratorsEliminated()
        );
    }

    private InterreductionReuse reducedBasis(BasisPreparation preparation, int maxSteps) {
        ReducedBasisMemo memo = preparation.reducedBasisMemo();
        synchronized (memo) {
            if (memo.completeResult != null) {
                return new InterreductionReuse(
                    memo.completeResult,
                    true,
                    memo.completeResult.steps()
                );
            }
            InterreductionResult computed = computeReducedBasis(preparation.basis(), maxSteps);
            if (computed.complete()) {
                memo.completeResult = computed;
            }
            return new InterreductionReuse(computed, false, 0);
        }
    }

    private InterreductionResult computeReducedBasis(List<Polynomial> basis, int maxSteps) {
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

    public record BasisPreparation(
        List<Polynomial> basis,
        PolynomialReducer.PreparedBasis preparedReducers,
        ReducedBasisMemo reducedBasisMemo,
        int steps,
        int reusablePreparationSteps,
        String budgetStatus,
        boolean budgetExhausted,
        int pairsConsidered,
        int pairsReduced,
        int pairsSkippedByProductCriterion,
        int pairsSkippedByChainCriterion,
        int maxPendingPairs,
        int initialGeneratorsConsidered,
        int initialGeneratorsReduced,
        int initialGeneratorsEliminated,
        int incrementalBaseSize,
        int extensionGeneratorsConsidered,
        int extensionGeneratorsReduced,
        int extensionGeneratorsEliminated
    ) {
        public BasisPreparation {
            basis = List.copyOf(basis);
        }
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
        int maxPendingPairs,
        boolean basisCacheHit,
        int basisPreparationSteps,
        int basisPreparationStepsSaved,
        int queryReductionSteps,
        int interreductionSteps,
        boolean reducedBasisCacheHit,
        int reducedBasisStepsSaved,
        int initialGeneratorsConsidered,
        int initialGeneratorsReduced,
        int initialGeneratorsEliminated,
        int incrementalBaseSize,
        int extensionGeneratorsConsidered,
        int extensionGeneratorsReduced,
        int extensionGeneratorsEliminated
    ) {
    }

    static final class ReducedBasisMemo {
        private InterreductionResult completeResult;
    }

    private record InterreductionResult(List<Polynomial> basis, int steps, boolean complete) {
    }

    private record InterreductionReuse(
        InterreductionResult result,
        boolean cacheHit,
        int stepsSaved
    ) {
    }
}
