package de.regelsuche.solver.portfolio;

/** Deterministic ordering and stopping policy for a portfolio request. */
public enum PortfolioPolicy {
    CAPABILITY_FIRST,
    COUNTEREXAMPLE_FIRST,
    CHEAPEST_CONFIRMATION_FIRST,
    INDEPENDENT_CONFIRMATION
}
