package de.regelsuche.solver.portfolio;

/** Semantic role of one backend result inside a portfolio. */
public enum BackendRole {
    SEARCH_GUIDANCE,
    ORACLE_VALIDATION,
    COUNTEREXAMPLE,
    SYMBOLIC_CONFIRMATION,
    FORMAL_PROOF;

    public boolean contributesTo(SolverObjective objective) {
        return switch (objective) {
            case SEARCH_GUIDANCE -> this == SEARCH_GUIDANCE;
            case VALIDATION -> this != SEARCH_GUIDANCE;
            case COUNTEREXAMPLE_SEARCH -> this != SEARCH_GUIDANCE;
            case SYMBOLIC_CONFIRMATION -> this != SEARCH_GUIDANCE;
            case FORMAL_PROOF -> true;
        };
    }

    public boolean canConfirm(SolverObjective objective) {
        return switch (objective) {
            case SEARCH_GUIDANCE -> this == SEARCH_GUIDANCE;
            case VALIDATION -> this == ORACLE_VALIDATION
                || this == SYMBOLIC_CONFIRMATION
                || this == FORMAL_PROOF;
            case COUNTEREXAMPLE_SEARCH -> false;
            case SYMBOLIC_CONFIRMATION -> this == SYMBOLIC_CONFIRMATION
                || this == FORMAL_PROOF;
            case FORMAL_PROOF -> this == FORMAL_PROOF;
        };
    }

    public boolean canRefute() {
        return this != SEARCH_GUIDANCE;
    }
}
