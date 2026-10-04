package de.regelsuche.sdk.optimization;

/** Operations retain Java division, remainder, narrowing and exact-check distinctions. */
public enum NumericOperation {
    ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER, NEGATE, ABS,
    AND, OR, XOR, NOT, SHIFT_LEFT, SHIFT_RIGHT, UNSIGNED_SHIFT_RIGHT,
    ADD_EXACT, SUBTRACT_EXACT, MULTIPLY_EXACT, NEGATE_EXACT,
    MOD, MOD_POW, MOD_MULTIPLY, POW
}
