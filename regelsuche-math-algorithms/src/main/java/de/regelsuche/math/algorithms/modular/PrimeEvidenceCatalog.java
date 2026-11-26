package de.regelsuche.math.algorithms.modular;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static de.regelsuche.math.algorithms.modular.PrimeEvidence.*;

/** Local witness data. Callers must verify any returned certificate against their observed modulus. */
public final class PrimeEvidenceCatalog {
    private PrimeEvidenceCatalog() {}

    // Curve, generator and subgroup order: Bouncy Castle bc-java commit
    // 6560e91d0a164b8ab58266f141ee6daeec21a340, org.bouncycastle.crypto.bls.BLS12_381G1.
    // The Lucas factorization and witness below are independently checked by the verifier.
    private static final BigInteger FIELD = hex(
            "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab");
    private static final Certificate ORDER = new Lucas(
            new BigInteger("52435875175126190479447740508185965837690552500527637822603658699938581184513"),
            BigInteger.valueOf(7), List.of(factor(2, 32), factor(3, 1), factor(11, 1), factor(19, 1),
                    factor(10177, 1), factor(125527, 1), factor(859267, 1), factor(906349, 2),
                    factor(2508409, 1), factor(2529403, 1), factor(52437899, 1), factor(254760293, 2)));
    private static final Certificate FIELD_PROOF = new EllipticOrder(FIELD, BigInteger.ZERO, BigInteger.valueOf(4),
            hex("17f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb"),
            hex("08b3f481e3aaa0f1a09e30ed741d8ae4fcf5e095d5d00af600db18cb2c04b3edd03cc744a2888ae40caa232946c5e7e1"),
            ORDER);

    /** Supplies a trial-division request for small inputs or a known witness for larger inputs. */
    public static Optional<Certificate> forModulus(BigInteger modulus) {
        if (!PrimeEvidence.bounded(modulus) || modulus.compareTo(BigInteger.TWO) < 0) return Optional.empty();
        if (modulus.bitLength() <= 31) return Optional.of(new SmallPrime(modulus));
        return FIELD.equals(modulus) ? Optional.of(FIELD_PROOF) : Optional.empty();
    }

    private static Factor factor(int prime, int exponent) {
        return new Factor(new SmallPrime(BigInteger.valueOf(prime)), exponent);
    }

    private static BigInteger hex(String digits) { return new BigInteger(digits, 16); }
}
