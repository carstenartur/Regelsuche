package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrimeFieldTest {
    @Test
    void canonicalArithmeticUsesTheDeclaredPrimeModulus() {
        PrimeField field = PrimeField.of(5);

        assertEquals(BigInteger.valueOf(5), field.characteristic());
        assertEquals(BigInteger.valueOf(4), field.canonical(
            BigInteger.valueOf(-1)));
        assertEquals(BigInteger.ONE, field.add(
            BigInteger.valueOf(4),
            BigInteger.valueOf(2)));
        assertEquals(BigInteger.valueOf(2), field.multiply(
            BigInteger.valueOf(4),
            BigInteger.valueOf(3)));
        assertEquals(BigInteger.valueOf(4), field.divide(
            BigInteger.valueOf(3),
            BigInteger.valueOf(2)));
        assertEquals(BigInteger.valueOf(4), field.negate(
            BigInteger.ONE));
    }

    @Test
    void modulusParticipatesInPolynomialRingIdentity() {
        PolynomialVariable variable = new PolynomialVariable("x");
        PolynomialRing<BigInteger> five = new PolynomialRing<>(
            PrimeField.of(5),
            List.of(variable),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        PolynomialRing<BigInteger> anotherFive = new PolynomialRing<>(
            PrimeField.of(5),
            List.of(variable),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        PolynomialRing<BigInteger> seven = new PolynomialRing<>(
            PrimeField.of(7),
            List.of(variable),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);

        assertEquals(five, anotherFive);
        assertNotEquals(five, seven);
    }

    @Test
    void sharedPredicateDefinesTheAcceptedPrimeModuli() {
        assertTrue(PrimeField.isPrimeModulus(2));
        assertTrue(PrimeField.isPrimeModulus(65_521));
        assertFalse(PrimeField.isPrimeModulus(1));
        assertFalse(PrimeField.isPrimeModulus(15));
        assertFalse(PrimeField.isPrimeModulus(Integer.MAX_VALUE - 1));
    }

    @Test
    void compositeModuliAndZeroDivisionFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
            PrimeField.of(1));
        assertThrows(IllegalArgumentException.class, () ->
            PrimeField.of(15));
        assertThrows(ArithmeticException.class, () ->
            PrimeField.of(7).divide(
                BigInteger.ONE,
                BigInteger.valueOf(14)));
    }
}
