package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import java.math.BigInteger;

/** Binary Jacobi symbol for positive odd denominators, including composite denominators. */
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
        var a = numerator.mod(n);
        int symbol = 1;
        while (a.signum() != 0) {
            if (work != null) work.charge(1L + SharedPowerVerifier.words(n) * SharedPowerVerifier.words(a));
            int twos = a.getLowestSetBit();
            a = a.shiftRight(twos);
            int residue = n.intValue() & 7;
            if ((twos & 1) != 0 && (residue == 3 || residue == 5)) symbol = -symbol;
            if (a.testBit(1) && n.testBit(1)) symbol = -symbol;
            var next = n.mod(a);
            n = a;
            a = next;
        }
        return n.equals(BigInteger.ONE) ? symbol : 0;
    }
}
