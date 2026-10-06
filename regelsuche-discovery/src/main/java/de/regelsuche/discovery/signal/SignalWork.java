package de.regelsuche.discovery.signal;

/**
 * Deterministic diagnostic work, separate from the runner's search events.
 * Units count explicit integer tests/divisions, rational operations and logical
 * coefficient slots. Operand bits describe arithmetic size, not bit complexity:
 * BigInteger internals, allocation bytes, hashing, loop control and CPU time are
 * deliberately not estimated by this model.
 */
public record SignalWork(long integerOperations, long rationalOperations,
                         long coefficientCells, long operandBits, int maxOperandBits) {
    public static final SignalWork ZERO = new SignalWork(0, 0, 0, 0, 0);

    public SignalWork {
        if (integerOperations < 0 || rationalOperations < 0 || coefficientCells < 0
                || operandBits < 0 || maxOperandBits < 0) {
            throw new IllegalArgumentException("work counters must be nonnegative");
        }
    }

    public long units() {
        return Math.addExact(Math.addExact(integerOperations, rationalOperations), coefficientCells);
    }

    public SignalWork plus(SignalWork other) {
        return new SignalWork(Math.addExact(integerOperations, other.integerOperations),
            Math.addExact(rationalOperations, other.rationalOperations),
            Math.addExact(coefficientCells, other.coefficientCells),
            Math.addExact(operandBits, other.operandBits),
            Math.max(maxOperandBits, other.maxOperandBits));
    }
}
