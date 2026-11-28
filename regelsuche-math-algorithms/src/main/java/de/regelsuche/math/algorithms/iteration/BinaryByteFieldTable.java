package de.regelsuche.math.algorithms.iteration;

import java.util.Optional;

/** Checks multiplication tables in the polynomial basis 1, x, ..., x^7 over GF(2). */
public final class BinaryByteFieldTable {
    private static final int ORDER = 256;
    private BinaryByteFieldTable() {}

    /**
     * Evidence about the values inspected during verification. Ownership or future stability
     * of a caller's table is a separate obligation whenever that table can be mutated.
     */
    public static final class Verified {
        private final int modulus;
        private Verified(int modulus) { this.modulus = modulus; }
        /** Monic degree-eight polynomial, including the bit for x^8. */
        public int modulus() { return modulus; }
        public int productsChecked() { return ORDER * ORDER; }
    }

    /**
     * Observing x^7 times x gives the low eight coefficients of the modulus. Prove that
     * modulus irreducible and independently reconstruct every cell by polynomial arithmetic.
     */
    public static Optional<Verified> verify(byte[][] table) {
        if (table == null || table.length != ORDER) return Optional.empty();
        // Copy row references once. Each mutable row is copied before examining its values.
        var snapshot = table.clone();
        for (int i = 0; i < ORDER; i++) {
            byte[] row = snapshot[i];
            if (row == null || row.length != ORDER) return Optional.empty();
            snapshot[i] = row.clone();
        }
        int modulus = ORDER | Byte.toUnsignedInt(snapshot[128][2]);
        // Every reducible degree-eight polynomial has a monic factor of degree at most four.
        for (int divisor = 2; divisor < 32; divisor++) {
            if (remainder(modulus, divisor) == 0) return Optional.empty();
        }
        for (int coefficient = 0; coefficient < ORDER; coefficient++) {
            for (int input = 0; input < ORDER; input++) {
                int product = 0;
                for (int bits = input; bits != 0; bits &= bits - 1) {
                    product ^= coefficient << Integer.numberOfTrailingZeros(bits);
                }
                if (Byte.toUnsignedInt(snapshot[coefficient][input]) != remainder(product, modulus)) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(new Verified(modulus));
    }

    private static int remainder(int dividend, int divisor) {
        int degree = Integer.highestOneBit(divisor);
        for (int bit = Integer.highestOneBit(dividend); bit >= degree; bit >>>= 1) {
            if ((dividend & bit) != 0) dividend ^= divisor * (bit / degree);
        }
        return dividend;
    }
}
