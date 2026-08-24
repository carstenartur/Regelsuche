package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class RationalExactScalarAdapterTest {

    @Test
    void legacyFacadeDelegatesCanonicalArithmeticToCoreContract() {
        Rational legacy = new Rational(
            BigInteger.valueOf(6),
            BigInteger.valueOf(-8));
        ExactRational exact = new ExactRational(
            BigInteger.valueOf(6),
            BigInteger.valueOf(-8));

        assertEquals(exact, legacy.exactValue());
        assertEquals(
            Rational.fromExact(
                exact.add(
                    new ExactRational(
                        BigInteger.ONE,
                        BigInteger.valueOf(2)))),
            legacy.add(
                new Rational(
                    BigInteger.ONE,
                    BigInteger.valueOf(2))));
        assertEquals("-3/4", legacy.toString());
    }

    @Test
    void legacyConstructorPreservesHistoricalZeroDenominatorFailure() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new Rational(
                BigInteger.ONE,
                BigInteger.ZERO));
    }
}
