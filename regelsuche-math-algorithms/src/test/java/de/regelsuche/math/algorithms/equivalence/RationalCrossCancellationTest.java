package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RationalCrossCancellationTest {
    @Test
    void multiplicationCancelsLargeCrossFactorsBeforeProducts() {
        BigInteger huge = BigInteger.ONE.shiftLeft(4096).subtract(BigInteger.valueOf(159));
        Rational left = new Rational(huge.multiply(BigInteger.valueOf(17)), BigInteger.valueOf(19));
        Rational right = new Rational(BigInteger.valueOf(19), huge.multiply(BigInteger.valueOf(23)));

        assertEquals(new Rational(BigInteger.valueOf(17), BigInteger.valueOf(23)), left.multiply(right));
    }

    @Test
    void divisionCancelsNumeratorsAndDenominatorsAcrossOperands() {
        BigInteger common = BigInteger.ONE.shiftLeft(3072).add(BigInteger.valueOf(12345));
        Rational left = new Rational(common.multiply(BigInteger.valueOf(5)), BigInteger.valueOf(7));
        Rational right = new Rational(common.multiply(BigInteger.valueOf(11)), BigInteger.valueOf(13));

        assertEquals(new Rational(BigInteger.valueOf(65), BigInteger.valueOf(77)), left.divide(right));
        assertThrows(ArithmeticException.class, () -> left.divide(Rational.ZERO));
    }

    @Test
    void additionUsesSharedDenominatorFactorsWithoutChangingCanonicalResult() {
        BigInteger common = BigInteger.ONE.shiftLeft(2048);
        Rational left = new Rational(BigInteger.valueOf(3), common.multiply(BigInteger.valueOf(5)));
        Rational right = new Rational(BigInteger.valueOf(7), common.multiply(BigInteger.valueOf(11)));

        assertEquals(
            new Rational(BigInteger.valueOf(68), common.multiply(BigInteger.valueOf(55))),
            left.add(right)
        );
        assertEquals(Rational.ZERO, left.add(left.negate()));
    }

    @Test
    void identityFastPathsReuseCanonicalConstantsByValue() {
        Rational value = new Rational(BigInteger.valueOf(7), BigInteger.valueOf(13));
        Rational independentlyConstructedNegativeOne = new Rational(BigInteger.valueOf(-17), BigInteger.valueOf(17));

        assertSame(value, value.add(Rational.ZERO));
        assertSame(value, value.subtract(Rational.ZERO));
        assertSame(value, value.multiply(Rational.ONE));
        assertSame(value, value.divide(Rational.ONE));
        assertSame(Rational.ZERO, value.multiply(Rational.ZERO));
        assertSame(Rational.NEGATIVE_ONE, Rational.ONE.negate());
        assertSame(Rational.ONE, Rational.NEGATIVE_ONE.negate());
        assertSame(Rational.ONE, independentlyConstructedNegativeOne.negate());
        assertEquals(value, independentlyConstructedNegativeOne.multiply(value).negate());
    }

    @Test
    void optimizedOperationsMatchDirectExactFormulasForDeterministicSamples() {
        Random random = new Random(20260807L);
        for (int i = 0; i < 500; i++) {
            Rational left = randomRational(random);
            Rational right = randomRational(random);

            Rational expectedSum = new Rational(
                left.numerator().multiply(right.denominator())
                    .add(right.numerator().multiply(left.denominator())),
                left.denominator().multiply(right.denominator())
            );
            Rational expectedProduct = new Rational(
                left.numerator().multiply(right.numerator()),
                left.denominator().multiply(right.denominator())
            );

            assertEquals(expectedSum, left.add(right));
            assertEquals(expectedProduct, left.multiply(right));
            assertEquals(
                left.numerator().multiply(right.denominator())
                    .compareTo(right.numerator().multiply(left.denominator())),
                Integer.signum(left.compareTo(right))
            );
            if (!right.isZero()) {
                Rational expectedQuotient = new Rational(
                    left.numerator().multiply(right.denominator()),
                    left.denominator().multiply(right.numerator())
                );
                assertEquals(expectedQuotient, left.divide(right));
            }
        }
    }

    private Rational randomRational(Random random) {
        long numerator = random.nextInt(2001) - 1000;
        long denominator = 1 + random.nextInt(1000);
        return new Rational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }
}
