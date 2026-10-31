package de.regelsuche.ast.iteration;

import java.math.BigInteger;

/** All source operators must belong to this one ring. Java division is never a ring operation. */
public enum IntegerArithmetic {
    EXACT(0), JAVA_INT(32), JAVA_LONG(64);
    private final int bits;
    IntegerArithmetic(int bits) { this.bits = bits; }
    public int bits() { return bits; }
    /** Signed canonical representative; callers must first establish integer-ring membership. */
    public BigInteger normalize(BigInteger integer) {
        if (bits == 0) return integer;
        BigInteger modulus = BigInteger.ONE.shiftLeft(bits), word = integer.mod(modulus);
        return word.testBit(bits - 1) ? word.subtract(modulus) : word;
    }
}
