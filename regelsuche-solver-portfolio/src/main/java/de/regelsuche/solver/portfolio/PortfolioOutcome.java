package de.regelsuche.solver.portfolio;

/** Aggregate outcome without upgrading weaker evidence into proof. */
public enum PortfolioOutcome {
    CONFIRMED,
    REFUTED,
    CONFLICT,
    INCONCLUSIVE,
    UNSUPPORTED,
    TIMEOUT,
    CANCELLED,
    BUDGET_EXHAUSTED,
    ERROR
}
