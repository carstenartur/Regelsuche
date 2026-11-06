package de.regelsuche.evolution;

/** Frozen, persisted capability sets shared by primitive and learned comparison arms. */
public enum TaskConstructionProfile {
    NONE,
    /** Bounded rational-polynomial preparation/composition; no unrestricted neutral-term insertion. */
    STRUCTURAL_V1,
    /** V1 plus bounded quartic synthesis and residual composition on expanded sums. */
    STRUCTURAL_V2,
    /** V2 plus bounded, source-derived auxiliaries and their exploration policy. */
    STRUCTURAL_AUX_V1,
    /** AUX plus paid, exact rational square preparation and recognition. */
    STRUCTURAL_SQUARE_V1,
    /** Square-capable construction plus existing distributive and normalization rules. */
    STRUCTURAL_EXPANSION_V1,
    /** Also permits deferred expansion as a bridge for other structural goals. */
    STRUCTURAL_EXPANSION_BRIDGE_V1;

    boolean requiresExactPolynomialSteps() {
        return this == STRUCTURAL_SQUARE_V1 || this == STRUCTURAL_EXPANSION_V1 || this == STRUCTURAL_EXPANSION_BRIDGE_V1;
    }

    CheckedSchemaTaskSolver attach(CheckedSchemaTaskSolver solver) {
        if (this == NONE) return solver;
        boolean expansion = this == STRUCTURAL_EXPANSION_V1 || this == STRUCTURAL_EXPANSION_BRIDGE_V1;
        boolean square = this == STRUCTURAL_SQUARE_V1 || expansion;
        boolean auxiliary = this == STRUCTURAL_AUX_V1 || square;
        var operations = new java.util.ArrayList<>(java.util.List.of(
            StructuralConstructionPrograms.Operation.EXPOSE_SQUARE,
            StructuralConstructionPrograms.Operation.COMPLETE_SQUARE,
            StructuralConstructionPrograms.Operation.FACTOR_DIFFERENCE,
            StructuralConstructionPrograms.Operation.FACTOR_PARTITION,
            StructuralConstructionPrograms.Operation.RESIDUAL_BALANCE));
        if (this == STRUCTURAL_V2 || auxiliary) {
            operations.add(StructuralConstructionPrograms.Operation.BINARY_QUARTIC);
            operations.add(StructuralConstructionPrograms.Operation.RESIDUAL_EXPANDED);
        }
        if (auxiliary) {
            operations.add(StructuralConstructionPrograms.Operation.AUXILIARY_COMPLEMENT);
            operations.add(StructuralConstructionPrograms.Operation.AUXILIARY_FACTOR);
            operations.add(square
                ? StructuralConstructionPrograms.Operation.RECOGNIZE_METERED_SQUARE
                : StructuralConstructionPrograms.Operation.RECOGNIZE_SQUARE);
            if (square) {
                operations.add(StructuralConstructionPrograms.Operation.RECOGNIZE_RATIONAL_SQUARE);
                operations.add(StructuralConstructionPrograms.Operation.RATIONAL_EXPOSE_SQUARE);
            }
        }
        var extended = solver.withConstructions(operations, auxiliary);
        if (square) extended = extended.withExactPolynomialSteps();
        return expansion ? extended.withExpansionRules(this == STRUCTURAL_EXPANSION_BRIDGE_V1) : extended;
    }
}
