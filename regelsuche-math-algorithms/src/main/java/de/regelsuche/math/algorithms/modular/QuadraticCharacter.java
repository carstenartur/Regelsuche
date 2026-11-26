package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import java.math.BigInteger;

/** Word-gap binary Jacobi with a positive-long tail, including odd composite denominators. */
public final class QuadraticCharacter {
    private QuadraticCharacter() {}

    public static int jacobi(BigInteger numerator, BigInteger denominator) {
        return jacobi(numerator, denominator, null);
    }

    static int jacobi(BigInteger numerator, BigInteger denominator, CertificateWork work) {
        SharedPowerPlan.bounded(numerator);
        SharedPowerPlan.bounded(denominator);
        if (denominator.signum() <= 0 || !denominator.testBit(0)) throw new IllegalArgumentException("ODD_DENOMINATOR_REQUIRED");
        var n = denominator;
        // Normalization is real bounded work even for zero, multiples of n, or n=1.
        if (work != null) work.charge(1L + SharedPowerVerifier.words(numerator) * SharedPowerVerifier.words(n));
        var a = numerator.mod(n);
        int symbol = 1;
        // Subtraction need not preserve a<n. Both bounds are required before longValue().
        while (a.signum() != 0 && (a.bitLength() > 63 || n.bitLength() > 63)) {
            chargeLinear(work, a, n);
            int twos = a.getLowestSetBit();
            a = a.shiftRight(twos);
            int residue = n.intValue() & 7;
            if ((twos & 1) != 0 && (residue == 3 || residue == 5)) symbol = -symbol;
            if (a.compareTo(n) < 0) {
                if (a.testBit(1) && n.testBit(1)) symbol = -symbol;
                var swap = n;
                n = a;
                a = swap;
            }
            // Both reductions preserve the symbol; remainder avoids long imbalanced subtraction runs.
            if (a.bitLength() - n.bitLength() >= 32) {
                if (work != null) work.charge(1L + SharedPowerVerifier.words(a) * SharedPowerVerifier.words(n));
                a = a.mod(n);
            } else {
                chargeLinear(work, a, n);
                a = a.subtract(n);
            }
        }
        if (a.signum() == 0) return n.equals(BigInteger.ONE) ? symbol : 0;
        return positiveLongTail(a.longValue(), n.longValue(), symbol, work);
    }

    /** Charge linear shift/comparison or subtraction work before the corresponding operations. */
    private static void chargeLinear(CertificateWork work, BigInteger a, BigInteger n) {
        if (work != null) work.charge(1L + Math.max(SharedPowerVerifier.words(a), SharedPowerVerifier.words(n)));
    }

    private static int positiveLongTail(long a, long n, int symbol, CertificateWork work) {
        while (a != 0) {
            // Both positive operands occupy one model word: the Euclidean round costs 1+1*1.
            if (work != null) work.charge(2);
            int twos = Long.numberOfTrailingZeros(a);
            a >>>= twos;
            int residue = (int) n & 7;
            if ((twos & 1) != 0 && (residue == 3 || residue == 5)) symbol = -symbol;
            if ((a & n & 2) != 0) symbol = -symbol;
            long next = n % a;
            n = a;
            a = next;
        }
        return n == 1 ? symbol : 0;
    }
}
