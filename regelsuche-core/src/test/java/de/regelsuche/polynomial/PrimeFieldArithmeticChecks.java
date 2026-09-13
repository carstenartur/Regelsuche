package de.regelsuche.polynomial;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Differential controls against arbitrary-precision modular arithmetic. */
final class PrimeFieldArithmeticChecks {
    private PrimeFieldArithmeticChecks() { }

    static void canonicalResiduesDoNotNeedAnotherReduction() {
        var field = PrimeField.of(101);
        for (String text : List.of("0", "1", "17", "100")) {
            var value = new BigInteger(text);
            require(field.canonical(value) == value,
                "an already canonical immutable residue must be reused");
        }
        for (String text : List.of("-1", "101", "102", "-202")) {
            var value = new BigInteger(text);
            require(field.canonical(value).equals(value.mod(field.modulus())), "reduction boundary");
        }
    }

    static void exhaustiveSmallFieldsMatchBigInteger() {
        for (int prime : new int[] {2, 3, 5, 7, 11, 31}) {
            var field = PrimeField.of(prime);
            for (int a = -prime; a <= 2 * prime; a++) {
                for (int b = -prime; b <= 2 * prime; b++) {
                    checkPair(field, BigInteger.valueOf(a), BigInteger.valueOf(b));
                }
            }
        }
    }

    static void largeInputsAndLargestModulusRemainExact() {
        Random random = new Random(20260913L);
        for (int prime : new int[] {2, 101, 65537, 1000000007, Integer.MAX_VALUE}) {
            var field = PrimeField.of(prime);
            var p = field.modulus();
            var edges = List.of(BigInteger.ZERO, BigInteger.ONE, p.subtract(BigInteger.ONE), p,
                p.add(BigInteger.ONE), p.negate(), BigInteger.ONE.shiftLeft(4096).negate(),
                BigInteger.ONE.shiftLeft(4096).add(p.subtract(BigInteger.ONE)));
            for (var a : edges) for (var b : edges) checkPair(field, a, b);
            for (int i = 0; i < 240; i++) {
                var a = new BigInteger(1 + random.nextInt(4096), random);
                var b = new BigInteger(1 + random.nextInt(4096), random);
                checkPair(field, random.nextBoolean() ? a : a.negate(),
                    random.nextBoolean() ? b : b.negate());
            }
        }
    }

    static void invalidInputsAndDomainIdentityAreUnchanged() {
        for (int invalid : new int[] {Integer.MIN_VALUE, -1, 0, 1, 4, 9, 25, 341, Integer.MAX_VALUE - 1}) {
            rejects(IllegalArgumentException.class, () -> PrimeField.of(invalid));
        }
        var field = PrimeField.of(101);
        var zero = BigInteger.ZERO;
        rejects(NullPointerException.class, () -> field.canonical(null));
        rejects(NullPointerException.class, () -> field.add(null, zero));
        rejects(NullPointerException.class, () -> field.add(zero, null));
        rejects(NullPointerException.class, () -> field.multiply(null, zero));
        rejects(NullPointerException.class, () -> field.multiply(zero, null));
        rejects(NullPointerException.class, () -> field.divide(zero, null));
        rejects(NullPointerException.class, () -> field.divide(null, BigInteger.ONE));
        // Divisor validation deliberately precedes dividend validation, as before.
        rejects(ArithmeticException.class, () -> field.divide(null, zero));
        require(field.equals(PrimeField.of(101)) && !field.equals(PrimeField.of(103)), "domain identity");
        require(field.hashCode() == Integer.hashCode(101), "domain hash");
        require(field.id().equals("regelsuche.coefficients.prime-field/v1/p=101"), "stable domain id");
        require(field.toString().equals("F_101"), "display identity");
    }

    static void immutableFieldCanBeSharedAcrossThreads() throws Exception {
        var field = PrimeField.of(Integer.MAX_VALUE);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var jobs = java.util.stream.IntStream.range(0, 8).<Callable<Void>>mapToObj(index -> () -> {
                for (int i = 0; i < 128; i++) {
                    checkPair(field, BigInteger.valueOf(index - 4L).shiftLeft(i),
                        BigInteger.valueOf(i - 64L).shiftLeft(index));
                }
                return null;
            }).toList();
            for (var future : pool.invokeAll(jobs)) future.get();
        }
    }

    private static void checkPair(PrimeField field, BigInteger a, BigInteger b) {
        BigInteger p = field.modulus(), ar = a.mod(p), br = b.mod(p);
        require(field.canonical(a).equals(ar), "canonical residue");
        require(field.fromInteger(a).equals(ar), "integer conversion");
        require(field.add(a, b).equals(ar.add(br).mod(p)), "exact modular sum");
        require(field.multiply(a, b).equals(ar.multiply(br).mod(p)), "exact modular product");
        require(field.negate(a).equals(ar.negate().mod(p)), "exact modular negation");
        require(field.isZero(a) == (ar.signum() == 0), "zero predicate");
        require(field.canonicalText(a).equals(ar.toString()), "canonical text");
        require(field.bitLength(a) == ar.bitLength(), "residue bit length");
        if (br.signum() == 0) {
            rejects(ArithmeticException.class, () -> field.divide(a, b));
        } else {
            require(field.divide(a, b).equals(ar.multiply(br.modInverse(p)).mod(p)), "exact modular quotient");
        }
    }

    private static void rejects(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("unexpected exception", error);
        }
        throw new AssertionError("expected " + type.getName());
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    public static void main(String[] args) throws Exception {
        canonicalResiduesDoNotNeedAnotherReduction();
        exhaustiveSmallFieldsMatchBigInteger();
        largeInputsAndLargestModulusRemainExact();
        invalidInputsAndDomainIdentityAreUnchanged();
        immutableFieldCanBeSharedAcrossThreads();
        System.out.println("5 prime-field arithmetic checks passed");
    }
}
