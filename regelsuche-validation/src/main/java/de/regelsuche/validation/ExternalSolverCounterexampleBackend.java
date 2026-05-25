package de.regelsuche.validation;

import java.util.Optional;

/**
 * Optional bridge for external SMT/solver-based counterexample search.
 *
 * <p>Implementations may call Z3, cvc5 or another backend. The default
 * implementation is intentionally no-op so deployments without solver binaries
 * keep deterministic local behavior.</p>
 */
public interface ExternalSolverCounterexampleBackend {
    Optional<CounterexampleSearchService.CounterexampleSearchResult> search(
        CounterexampleSearchService.HypothesisInput hypothesis,
        CounterexampleSearchService.CounterexampleBudget budget
    );

    static ExternalSolverCounterexampleBackend disabled() {
        return (hypothesis, budget) -> Optional.empty();
    }
}
