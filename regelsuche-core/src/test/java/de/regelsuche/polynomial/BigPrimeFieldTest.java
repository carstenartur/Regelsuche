package de.regelsuche.polynomial;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BigPrimeFieldTest {
    @Test void arithmeticDoesNotNarrowModulusOrResiduesToMachineWords() {
        BigInteger p = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE.shiftLeft(32)).subtract(BigInteger.valueOf(977));
        var field = BigPrimeField.assumingPrime(p);
        BigInteger a = p.subtract(BigInteger.TWO);
        assertEquals(BigInteger.valueOf(4), field.multiply(a, a));
        assertEquals(p.subtract(BigInteger.valueOf(4)), field.add(a, a));
        assertEquals(BigInteger.TWO, field.negate(a));
        assertEquals(a, field.divide(BigInteger.valueOf(4), a));
        assertEquals(p.subtract(BigInteger.ONE), field.canonical(BigInteger.valueOf(-1)));
        assertEquals(p, field.characteristic());
        assertThrows(ArithmeticException.class, () -> field.divide(BigInteger.ONE, BigInteger.ZERO));
    }

    @Test void assumptionsArePartOfTheDomainAndNeverPromotedToProof() {
        var proven = BigPrimeField.of(7);
        var assumed = BigPrimeField.assumingPrime(BigInteger.valueOf(7));
        assertEquals(BigPrimeField.Primality.DETERMINISTIC_INT, proven.primality());
        assertEquals(BigPrimeField.Primality.ASSUMED_PRIME, assumed.primality());
        assertNotEquals(proven, assumed);
        assertNotEquals(proven.id(), assumed.id());
        assertNotEquals(assumed, BigPrimeField.assumingPrime(BigInteger.valueOf(11)));
        assertThrows(IllegalArgumentException.class, () -> BigPrimeField.of(15));
        assertThrows(IllegalArgumentException.class, () -> BigPrimeField.assumingPrime(BigInteger.ONE));
    }
    @Test void explicitAssumptionStillRejectsImmediatelyDecidableComposites() {
        assertThrows(IllegalArgumentException.class, () -> BigPrimeField.assumingPrime(BigInteger.valueOf(15)));
        assertThrows(IllegalArgumentException.class, () -> BigPrimeField.assumingPrime(BigInteger.ONE.shiftLeft(128)));
        assertEquals(BigPrimeField.Primality.ASSUMED_PRIME,
            BigPrimeField.assumingPrime(BigInteger.TWO).primality());
    }
}
