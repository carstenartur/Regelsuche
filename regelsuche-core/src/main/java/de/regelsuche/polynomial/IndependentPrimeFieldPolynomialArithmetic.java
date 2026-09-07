package de.regelsuche.polynomial;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dense modular arithmetic used only by the independent original-domain
 * verifier.
 *
 * <p>Keeping this implementation in core and separate from the native
 * factorization module prevents accidental reuse of Berlekamp, suitable-prime
 * or backend evidence at the trust boundary.</p>
 */
final class IndependentPrimeFieldPolynomialArithmetic {
    private IndependentPrimeFieldPolynomialArithmetic() {
    }

    static int[] reduce(
        List<BigInteger> coefficients,
        int prime,
        PolynomialWorkSink work
    ) {
        int[] result = new int[coefficients.size()];
        BigInteger modulus = BigInteger.valueOf(prime);
        for (int index = 0; index < result.length; index++) {
            work.consume(
                "independent-irreducibility.prime-reduction",
                1);
            result[index] = coefficients.get(index)
                .mod(modulus)
                .intValueExact();
        }
        return trim(result);
    }

    static int[] monic(
        int[] polynomial,
        int prime,
        PolynomialWorkSink work
    ) {
        int inverse = inverse(
            polynomial[polynomial.length - 1],
            prime,
            work);
        int[] result = polynomial.clone();
        for (int index = 0; index < result.length; index++) {
            work.consume(
                "independent-irreducibility.monic-scaling",
                1);
            result[index] = mod((long) result[index] * inverse, prime);
        }
        return trim(result);
    }

    static int[] frobenius(
        int[] x,
        int iterations,
        int prime,
        int[] modulus,
        PolynomialWorkSink work
    ) {
        int[] result = x;
        for (int iteration = 0; iteration < iterations; iteration++) {
            result = powMod(result, prime, modulus, prime, work);
        }
        return result;
    }

    static int[] subtract(
        int[] left,
        int[] right,
        int prime,
        PolynomialWorkSink work
    ) {
        int[] result = new int[Math.max(left.length, right.length)];
        for (int index = 0; index < result.length; index++) {
            work.consume(
                "independent-irreducibility.polynomial-subtractions",
                1);
            int leftCoefficient = index < left.length ? left[index] : 0;
            int rightCoefficient = index < right.length ? right[index] : 0;
            result[index] = mod(
                (long) leftCoefficient - rightCoefficient,
                prime);
        }
        return trim(result);
    }

    static int[] gcd(
        int[] left,
        int[] right,
        int prime,
        PolynomialWorkSink work
    ) {
        int[] first = trim(left);
        int[] second = trim(right);
        while (second.length != 0) {
            int[] remainder = remainder(
                first,
                second,
                prime,
                work);
            first = second;
            second = remainder;
        }
        return first;
    }

    static List<Integer> distinctPrimeDivisors(int value) {
        ArrayList<Integer> result = new ArrayList<>();
        int remaining = value;
        for (int candidate = 2;
                (long) candidate * candidate <= remaining;
                candidate++) {
            if (remaining % candidate != 0) {
                continue;
            }
            result.add(candidate);
            while (remaining % candidate == 0) {
                remaining /= candidate;
            }
        }
        if (remaining > 1) {
            result.add(remaining);
        }
        return List.copyOf(result);
    }

    static int degree(int[] polynomial) {
        return trim(polynomial).length - 1;
    }

    private static int[] powMod(
        int[] base,
        int exponent,
        int[] modulus,
        int prime,
        PolynomialWorkSink work
    ) {
        int[] result = {1};
        int[] factor = base;
        int remaining = exponent;
        while (remaining > 0) {
            if ((remaining & 1) == 1) {
                result = multiplyMod(
                    result,
                    factor,
                    modulus,
                    prime,
                    work);
            }
            remaining >>>= 1;
            if (remaining > 0) {
                factor = multiplyMod(
                    factor,
                    factor,
                    modulus,
                    prime,
                    work);
            }
        }
        return result;
    }

    private static int[] multiplyMod(
        int[] left,
        int[] right,
        int[] modulus,
        int prime,
        PolynomialWorkSink work
    ) {
        if (left.length == 0 || right.length == 0) {
            return new int[0];
        }
        int[] product = new int[left.length + right.length - 1];
        for (int leftIndex = 0;
                leftIndex < left.length;
                leftIndex++) {
            for (int rightIndex = 0;
                    rightIndex < right.length;
                    rightIndex++) {
                work.consume(
                    "independent-irreducibility.frobenius-multiplications",
                    1);
                int index = leftIndex + rightIndex;
                product[index] = mod(
                    product[index]
                        + (long) left[leftIndex] * right[rightIndex],
                    prime);
            }
        }
        int modulusDegree = degree(modulus);
        for (int exponent = product.length - 1;
                exponent >= modulusDegree;
                exponent--) {
            int coefficient = product[exponent];
            if (coefficient == 0) {
                continue;
            }
            int offset = exponent - modulusDegree;
            for (int index = 0; index < modulusDegree; index++) {
                work.consume(
                    "independent-irreducibility.frobenius-reductions",
                    1);
                product[offset + index] = mod(
                    product[offset + index]
                        - (long) coefficient * modulus[index],
                    prime);
            }
            product[exponent] = 0;
        }
        return trim(Arrays.copyOf(product, modulusDegree));
    }

    private static int[] remainder(
        int[] dividend,
        int[] divisor,
        int prime,
        PolynomialWorkSink work
    ) {
        int[] result = trim(dividend).clone();
        int divisorDegree = degree(divisor);
        int inverse = inverse(
            divisor[divisorDegree],
            prime,
            work);
        while (degree(result) >= divisorDegree) {
            int resultDegree = degree(result);
            int factor = mod(
                (long) result[resultDegree] * inverse,
                prime);
            int offset = resultDegree - divisorDegree;
            for (int index = 0; index <= divisorDegree; index++) {
                work.consume(
                    "independent-irreducibility.gcd-remainder-steps",
                    1);
                result[offset + index] = mod(
                    result[offset + index]
                        - (long) factor * divisor[index],
                    prime);
            }
            result = trim(result);
        }
        return result;
    }

    private static int inverse(
        int value,
        int prime,
        PolynomialWorkSink work
    ) {
        for (int candidate = 1; candidate < prime; candidate++) {
            work.consume(
                "independent-irreducibility.modular-inverse-trials",
                1);
            if (mod((long) value * candidate, prime) == 1) {
                return candidate;
            }
        }
        throw new ArithmeticException(
            "nonzero field element has no inverse");
    }

    private static int[] trim(int[] polynomial) {
        int size = polynomial.length;
        while (size > 0 && polynomial[size - 1] == 0) {
            size--;
        }
        return size == polynomial.length
            ? polynomial
            : Arrays.copyOf(polynomial, size);
    }

    private static int mod(long value, int prime) {
        int result = (int) (value % prime);
        return result < 0 ? result + prime : result;
    }
}
