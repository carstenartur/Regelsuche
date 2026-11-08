package de.regelsuche.ast.iteration;

/**
 * Independent inversion operands in a single exact commutative group of units.
 * The caller supplies pure bindings and proves that every operand is a unit.
 */
public record FieldInversionBatch(int operands) {
    public static final int MAX_OPERANDS = 64;

    public FieldInversionBatch {
        if (operands < 0 || operands > MAX_OPERANDS) {
            throw new IllegalArgumentException("FIELD_BATCH_OPERAND_LIMIT");
        }
    }
}
