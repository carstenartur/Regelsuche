package de.regelsuche.scalar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class ExactRationalCrossCancellationTest {
    @Test
    void multiplicationCancelsLargeOpposingFactorsExactly() {
        BigInteger factor = BigInteger.ONE.shiftLeft(20_000).add(
            BigInteger.ONE);
        ExactRational left = new ExactRational(
            factor,
            BigInteger.valueOf(3));
        ExactRational right = new ExactRational(
            BigInteger.valueOf(3),
            factor);

        ExactRational product = left.multiply(right);

        assertEquals(ExactRational.ONE, product);
        assertTrue(product.numerator().bitLength() <= 1);
        assertTrue(product.denominator().bitLength() <= 1);
    }

    @Test
    void additionUsesTheDenominatorGcdBeforeBuildingTheLcm() {
        BigInteger common = BigInteger.ONE.shiftLeft(8_000);
        ExactRational first = new ExactRational(
            BigInteger.ONE,
            common.multiply(BigInteger.valueOf(3)));
        ExactRational second = new ExactRational(
            BigInteger.ONE,
            common.multiply(BigInteger.valueOf(5)));

        ExactRational sum = first.add(second);

        assertEquals(
            new ExactRational(
                BigInteger.valueOf(8),
                common.multiply(BigInteger.valueOf(15))),
            sum);
    }

    @Test
    void divisionReusesCrossCancelledMultiplication() {
        BigInteger factor = BigInteger.ONE.shiftLeft(12_000).add(
            BigInteger.valueOf(3));
        ExactRational dividend = new ExactRational(
            factor,
            BigInteger.valueOf(7));
        ExactRational divisor = new ExactRational(
            factor,
            BigInteger.valueOf(11));

        assertEquals(
            new ExactRational(
                BigInteger.valueOf(11),
                BigInteger.valueOf(7)),
            dividend.divide(divisor));
    }

    @Test
    void signAndAbsoluteValueRemainCanonical() {
        ExactRational negative = new ExactRational(
            BigInteger.valueOf(-6),
            BigInteger.valueOf(8));

        assertEquals(-1, negative.signum());
        assertEquals(
            new ExactRational(
                BigInteger.valueOf(3),
                BigInteger.valueOf(4)),
            negative.abs());
        assertEquals(0, ExactRational.ZERO.signum());
    }
}
