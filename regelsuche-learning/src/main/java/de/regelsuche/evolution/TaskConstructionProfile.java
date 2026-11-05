package de.regelsuche.evolution;

/** Frozen, persisted capability sets shared by primitive and learned comparison arms. */
public enum TaskConstructionProfile {
    NONE,
    /** Bounded rational-polynomial preparation/composition; no unrestricted neutral-term insertion. */
    STRUCTURAL_V1,
    /** V1 plus bounded quartic synthesis and residual composition on expanded sums. */
    STRUCTURAL_V2,
    /** V2 plus bounded, source-derived auxiliaries and their exploration policy. */
    STRUCTURAL_AUX_V1;

    CheckedSchemaTaskSolver attach(CheckedSchemaTaskSolver solver) {
        if (this == NONE) return solver;
        var operations = new java.util.ArrayList<>(java.util.List.of(
            StructuralConstructionPrograms.Operation.EXPOSE_SQUARE,
            StructuralConstructionPrograms.Operation.COMPLETE_SQUARE,
            StructuralConstructionPrograms.Operation.FACTOR_DIFFERENCE,
            StructuralConstructionPrograms.Operation.FACTOR_PARTITION,
            StructuralConstructionPrograms.Operation.RESIDUAL_BALANCE));
        if (this == STRUCTURAL_V2 || this == STRUCTURAL_AUX_V1) {
            operations.add(StructuralConstructionPrograms.Operation.BINARY_QUARTIC);
            operations.add(StructuralConstructionPrograms.Operation.RESIDUAL_EXPANDED);
        }
        if (this == STRUCTURAL_AUX_V1) {
            operations.add(StructuralConstructionPrograms.Operation.AUXILIARY_COMPLEMENT);
            operations.add(StructuralConstructionPrograms.Operation.AUXILIARY_FACTOR);
            operations.add(StructuralConstructionPrograms.Operation.RECOGNIZE_SQUARE);
        }
        return solver.withConstructions(operations, this == STRUCTURAL_AUX_V1);
    }
}
