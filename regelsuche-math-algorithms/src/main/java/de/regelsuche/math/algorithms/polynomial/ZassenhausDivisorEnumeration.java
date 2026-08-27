package de.regelsuche.math.algorithms.polynomial;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Complete bounded positive-divisor enumeration for leading coefficients. */
final class ZassenhausDivisorEnumeration {
    private ZassenhausDivisorEnumeration() {
    }

    static List<BigInteger> positiveDivisors(
        BigInteger value,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work
    ) {
        BigInteger magnitude = Objects.requireNonNull(
            value,
            "value").abs();
        if (magnitude.signum() == 0) {
            throw new IllegalArgumentException(
                "zero has infinitely many divisors");
        }
        ArrayList<BigInteger> result = new ArrayList<>();
        BigInteger candidate = BigInteger.ONE;
        long tests = 0;
        while (candidate.compareTo(
                magnitude.divide(candidate)) <= 0) {
            if (tests >= policy.maxLeadingDivisorTests()) {
                throw new IntegerPolynomialArithmetic.LimitReached(
                    "ZASSENHAUS_LEADING_DIVISOR_POLICY_EXCEEDED");
            }
            tests++;
            work.consume("zassenhaus.leading-divisors.tests", 1);
            BigInteger[] division =
                magnitude.divideAndRemainder(candidate);
            if (division[1].signum() == 0) {
                result.add(candidate);
                if (!division[0].equals(candidate)) {
                    result.add(division[0]);
                }
            }
            candidate = candidate.add(BigInteger.ONE);
        }
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }
}
