package de.regelsuche.evolution;

/** Frozen, persisted capability sets shared by primitive and learned comparison arms. */
public enum TaskConstructionProfile {
    NONE,
    /** Bounded rational-polynomial preparation/composition; no unrestricted neutral-term insertion. */
    STRUCTURAL_V1;

    CheckedSchemaTaskSolver attach(CheckedSchemaTaskSolver solver) {
        return this == NONE ? solver : solver.withConstructions();
    }
}
