package de.regelsuche.solver.portfolio;

/** Backend-independent objective requested by a caller such as #225 or #215. */
public enum SolverObjective {
    SEARCH_GUIDANCE,
    VALIDATION,
    COUNTEREXAMPLE_SEARCH,
    SYMBOLIC_CONFIRMATION,
    FORMAL_PROOF;

    public boolean proofObjective() {
        return this == SYMBOLIC_CONFIRMATION || this == FORMAL_PROOF;
    }
}
