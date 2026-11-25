package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Independent dense verifier arithmetic. This class never calls the search's
 * UnivariatePolynomialView operations, DDF or randomized splitting routines.
 */
final class IndependentBigPrimePolynomialArithmetic {
    private final BigInteger modulus;
    private final LargePrimeWorkBudget work;

    IndependentBigPrimePolynomialArithmetic(BigInteger modulus, LargePrimeWorkBudget work) {
        this.modulus = modulus;
        this.work = work;
    }

    BigInteger[] from(SparsePolynomial<BigInteger> source) {
        BigInteger[] result = zeros(source.degree(0) + 1L);
        for (var term : source.terms().entrySet()) {
            work.consume("verify.load", 1);
            result[term.getKey().exponent(0)] = term.getValue();
        }
        return result;
    }

    BigInteger[] constant(BigInteger value) {
        BigInteger[] result = zeros(1);
        result[0] = value;
        return result;
    }

    BigInteger[] multiply(BigInteger[] left, BigInteger[] right) {
        if (left.length == 0 || right.length == 0) return zeros(0);
        BigInteger[] result = zeros(left.length + (long) right.length - 1);
        for (int i = 0; i < left.length; i++) {
            for (int j = 0; j < right.length; j++) {
                work.consume("verify.multiply", 2);
                result[i + j] = result[i + j].add(left[i].multiply(right[j])).mod(modulus);
            }
        }
        return trim(result);
    }

    private BigInteger[] subtract(BigInteger[] left, BigInteger[] right) {
        BigInteger[] result = zeros(Math.max(left.length, right.length));
        for (int i = 0; i < result.length; i++) {
            work.consume("verify.subtract", 1);
            BigInteger a = i < left.length ? left[i] : BigInteger.ZERO;
            BigInteger b = i < right.length ? right[i] : BigInteger.ZERO;
            result[i] = a.subtract(b).mod(modulus);
        }
        return trim(result);
    }

    private BigInteger[] remainder(BigInteger[] dividend, BigInteger[] divisor) {
        if (divisor.length == 0) throw new ArithmeticException("zero polynomial divisor");
        if (dividend.length < divisor.length) return dividend;
        BigInteger[] result = copy(dividend, dividend.length);
        BigInteger inverse = BigInteger.ONE;
        if (!divisor[divisor.length - 1].equals(BigInteger.ONE)) {
            work.inversion("verify.inverse");
            inverse = divisor[divisor.length - 1].modInverse(modulus);
            work.consume("verify.inverse-complete", 0);
        }
        for (int i = dividend.length - 1; i >= divisor.length - 1; i--) {
            work.consume("verify.divide-step", 1);
            if (result[i].signum() == 0) continue;
            BigInteger scalar = result[i].multiply(inverse).mod(modulus);
            int shift = i - divisor.length + 1;
            for (int j = 0; j < divisor.length; j++) {
                work.consume("verify.divide-update", 2);
                result[shift + j] = result[shift + j].subtract(scalar.multiply(divisor[j])).mod(modulus);
            }
        }
        return trim(result);
    }

    private BigInteger[] gcd(BigInteger[] first, BigInteger[] second) {
        while (second.length != 0) {
            work.consume("verify.gcd", 1);
            BigInteger[] next = remainder(first, second);
            first = second;
            second = next;
        }
        return first;
    }

    private BigInteger[] power(BigInteger[] base, BigInteger exponent, BigInteger[] polynomial) {
        BigInteger[] result = constant(BigInteger.ONE);
        BigInteger[] reduced = remainder(base, polynomial);
        for (int bit = exponent.bitLength() - 1; bit >= 0; bit--) {
            work.consume("verify.power-bit", 1);
            result = remainder(multiply(result, result), polynomial);
            if (exponent.testBit(bit)) result = remainder(multiply(result, reduced), polynomial);
        }
        return result;
    }

    boolean irreducible(BigInteger[] polynomial) {
        int degree = polynomial.length - 1;
        if (degree <= 0) return false;
        if (degree == 1) {
            work.consume("verify.rabin-linear", 1);
            return true;
        }
        List<Integer> checkpoints = new ArrayList<>();
        int remaining = degree;
        for (int divisor = 2; (long) divisor * divisor <= remaining; divisor++) {
            work.consume("verify.degree-divisors", 1);
            if (remaining % divisor != 0) continue;
            work.reserve(1);
            checkpoints.add(degree / divisor);
            while (remaining % divisor == 0) {
                work.consume("verify.degree-divisors", 1);
                remaining /= divisor;
            }
        }
        if (remaining > 1) {
            work.reserve(1);
            checkpoints.add(degree / remaining);
        }
        BigInteger[] x = zeros(2);
        x[1] = BigInteger.ONE;
        BigInteger[] frobenius = x;
        for (int i = 1; i <= degree; i++) {
            work.consume("verify.rabin-round", 1);
            frobenius = power(frobenius, modulus, polynomial);
            if (checkpoints.contains(i) && gcd(polynomial, subtract(frobenius, x)).length != 1) return false;
        }
        work.consume("verify.rabin-final", degree + 1L);
        return equal(x, frobenius);
    }

    private BigInteger[] zeros(long size) {
        work.reserveVector(size, 1);
        work.consume("verify.allocate-initialize", size);
        BigInteger[] result = new BigInteger[(int) size];
        for (int i = 0; i < result.length; i++) {
            if ((i & 1023) == 0) work.checkpoint("verify.initialize");
            result[i] = BigInteger.ZERO;
        }
        work.checkpoint("verify.initialize");
        return result;
    }

    private BigInteger[] trim(BigInteger[] source) {
        int size = source.length;
        while (size > 0) {
            work.consume("verify.trim", 1);
            if (source[size - 1].signum() != 0) break;
            size--;
        }
        if (size == source.length) return source;
        return copy(source, size);
    }

    private BigInteger[] copy(BigInteger[] source, int size) {
        work.reserveVector(size, 1);
        BigInteger[] result = new BigInteger[size];
        for (int i = 0; i < size; i++) {
            if ((i & 1023) == 0) work.checkpoint("verify.copy");
            result[i] = source[i];
        }
        work.checkpoint("verify.copy");
        return result;
    }

    boolean equal(BigInteger[] left, BigInteger[] right) {
        if (left.length != right.length) return false;
        for (int i = 0; i < left.length; i++) {
            if ((i & 1023) == 0) work.checkpoint("verify.compare");
            if (!left[i].equals(right[i])) return false;
        }
        work.checkpoint("verify.compare");
        return true;
    }
}
