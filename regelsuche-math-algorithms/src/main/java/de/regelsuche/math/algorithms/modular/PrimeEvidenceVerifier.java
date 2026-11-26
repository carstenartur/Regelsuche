package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.polynomial.PrimeField;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.modular.PrimeEvidence.*;
import static java.math.BigInteger.*;

/** Deterministic replay of untrusted, modulus-bound evidence. No witness lookup is used here. */
public final class PrimeEvidenceVerifier {
    private static final int MAX_DEPTH = 64;
    private final CertificateWork work;

    private PrimeEvidenceVerifier(CertificateWork work) { this.work = work; }

    public record Result(boolean valid, String reason, long work) {}

    public static Result verify(BigInteger expectedModulus, Certificate certificate, IterationLimits limits,
            BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            work.charge(1);
            require(PrimeEvidence.bounded(expectedModulus) && certificate != null, "MISSING_OR_INVALID_INPUT");
            work.check(expectedModulus);
            require(expectedModulus.equals(certificate.modulus()), "MODULUS_MISMATCH");
            new PrimeEvidenceVerifier(work).replay(certificate, 1, 0);
            work.charge(0);
            return new Result(true, "VERIFIED", work.used());
        } catch (Invalid | CertificateWork.Stop failure) {
            return new Result(false, failure.getMessage(), work.used());
        }
    }

    private void replay(Certificate certificate, int depth, long retained) {
        work.cells(retained + 1);
        if (depth > MAX_DEPTH) throw new CertificateWork.Stop("PRIME_DEPTH_LIMIT");
        var n = work.check(certificate.modulus());
        require(n.compareTo(TWO) >= 0, "INVALID_MODULUS");
        switch (certificate) {
            case SmallPrime leaf -> {
                require(n.bitLength() <= 31, "SMALL_PRIME_RANGE");
                int candidate = n.intValueExact();
                // Reserve the full odd trial-divisor loop before entering the core's exact predicate.
                work.charge(n.testBit(0) ? 1L + (long) Math.sqrt(candidate) / 2 : 1);
                require(PrimeField.isPrimeModulus(candidate), "COMPOSITE_LEAF");
            }
            case Lucas lucas -> lucas(lucas, depth, retained + 1);
            case EllipticOrder curve -> elliptic(curve, depth, retained + 1);
        }
    }

    private void lucas(Lucas proof, int depth, long retained) {
        var n = proof.modulus();
        var witness = work.check(proof.witness());
        require(witness.signum() > 0 && witness.compareTo(n) < 0, "INVALID_LUCAS_WITNESS");
        require(!proof.factors().isEmpty(), "MISSING_FACTORIZATION");
        long cells = retained + proof.factors().size();
        work.cells(cells);
        var seen = new HashSet<BigInteger>();
        var nMinusOne = n.subtract(ONE);
        var remaining = nMinusOne;
        long cost = wordProduct(n);
        for (var factor : proof.factors()) {
            var q = work.check(factor.prime().modulus());
            require(q.compareTo(TWO) >= 0 && q.compareTo(n) < 0 && seen.add(q), "INVALID_LUCAS_FACTOR");
            require(factor.exponent() > 0 && factor.exponent() < n.bitLength(), "INVALID_MULTIPLICITY");
            // Divide the remaining product down: even hostile multiplicities cannot grow a temporary integer.
            for (int i = 0; i < factor.exponent(); i++) {
                work.charge(cost);
                var quotient = remaining.divideAndRemainder(q);
                require(quotient[1].signum() == 0, "FACTORIZATION_MISMATCH");
                remaining = quotient[0];
            }
            replay(factor.prime(), depth + 1, cells);
        }
        require(remaining.equals(ONE), "FACTORIZATION_MISMATCH");
        require(power(witness, nMinusOne, n, cost).equals(ONE), "FERMAT_MISMATCH");
        // For each prime divisor of n the witness has order n-1 by these prime-factor tests.
        for (var factor : proof.factors()) {
            work.charge(cost);
            var exponent = nMinusOne.divide(factor.prime().modulus());
            var residue = power(witness, exponent, n, cost);
            work.charge(4 * cost);
            require(residue.subtract(ONE).gcd(n).equals(ONE), "LUCAS_ORDER_MISMATCH");
        }
    }

    private BigInteger power(BigInteger base, BigInteger exponent, BigInteger n, long cost) {
        // Binary powering needs at most two modular products per exponent bit.
        work.charge(2L * exponent.bitLength() * cost);
        return base.modPow(exponent, n);
    }

    private void elliptic(EllipticOrder proof, int depth, long retained) {
        var n = proof.modulus();
        var order = work.check(proof.orderPrime().modulus());
        require(n.compareTo(valueOf(3)) > 0 && order.compareTo(n) < 0, "INVALID_ELLIPTIC_ORDER");
        long cost = wordProduct(n);
        work.charge(8 * cost);
        require(order.compareTo(n.sqrt().shiftLeft(1).add(ONE)) > 0, "INSUFFICIENT_ELLIPTIC_ORDER");
        work.cells(retained + 4);
        var x = work.check(proof.x());
        var y = work.check(proof.y());
        require(canonical(x, n) && canonical(y, n), "NONCANONICAL_POINT");
        var inputA = work.check(proof.a());
        var inputB = work.check(proof.b());
        work.charge(wordProduct(inputA) + wordProduct(inputB) + 20 * cost);
        var a = inputA.mod(n);
        var b = inputB.mod(n);
        var discriminant = a.pow(3).shiftLeft(2).add(b.pow(2).multiply(valueOf(27)));
        require(discriminant.multiply(valueOf(6)).gcd(n).equals(ONE), "SINGULAR_OR_BAD_CHARACTERISTIC");
        require(y.pow(2).subtract(x.pow(3).add(a.multiply(x)).add(b)).mod(n).signum() == 0, "OFF_CURVE");
        replay(proof.orderPrime(), depth + 1, retained + 4);

        // Every accepted sum reduces correctly modulo every prime divisor l of n. The finite point
        // then has prime order r modulo l. At most 2*l+1 points exist, so no l <= floor(sqrt(n)) exists.
        var point = new Point(x, y);
        var multiple = point;
        for (int bit = order.bitLength() - 2; bit >= 0; bit--) {
            multiple = add(multiple, multiple, a, n, cost);
            if (order.testBit(bit)) multiple = add(multiple, point, a, n, cost);
        }
        require(multiple == Point.INFINITY, "ELLIPTIC_ORDER_MISMATCH");
    }

    private record Point(BigInteger x, BigInteger y) {
        private static final Point INFINITY = new Point(null, null);
    }

    private Point add(Point left, Point right, BigInteger a, BigInteger n, long cost) {
        work.charge(1);
        if (left == Point.INFINITY) return right;
        if (right == Point.INFINITY) return left;
        work.charge(16 * cost); // field products, gcd and checked inverse of a full-width slope denominator
        BigInteger numerator;
        BigInteger denominator;
        if (left.x.equals(right.x)) {
            if (left.y.add(right.y).mod(n).signum() == 0) return Point.INFINITY;
            require(left.y.equals(right.y), "MIXED_ELLIPTIC_SUM");
            numerator = left.x.pow(2).multiply(valueOf(3)).add(a);
            denominator = left.y.shiftLeft(1);
        } else {
            numerator = right.y.subtract(left.y);
            denominator = right.x.subtract(left.x);
        }
        require(denominator.gcd(n).equals(ONE), "NONUNIT_ELLIPTIC_DENOMINATOR");
        var slope = numerator.multiply(denominator.modInverse(n)).mod(n);
        var x = slope.pow(2).subtract(left.x).subtract(right.x).mod(n);
        var y = slope.multiply(left.x.subtract(x)).subtract(left.y).mod(n);
        return new Point(x, y);
    }

    private static boolean canonical(BigInteger coordinate, BigInteger n) {
        return coordinate.signum() >= 0 && coordinate.compareTo(n) < 0;
    }

    /** Work unit for full-width modular multiplication/division at this operand size. */
    private static long wordProduct(BigInteger value) {
        long words = 1L + value.bitLength() / Long.SIZE;
        return words * words;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new Invalid(reason);
    }

    private static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private Invalid(String reason) { super(reason, null, false, false); }
    }
}
