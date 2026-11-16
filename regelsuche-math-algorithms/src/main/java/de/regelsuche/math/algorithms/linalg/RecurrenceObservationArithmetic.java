package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import java.math.BigInteger;

final class RecurrenceObservationArithmetic {
    static final int MAX_RATIONAL_BITS = 16_384;

    private RecurrenceObservationArithmetic() { }

    static Rational addProduct(Rational total, Rational left, Rational right, Work work) {
        work.add(1);
        if (left.isZero() || right.isZero()) return total;
        return add(total, multiply(left, right, work), work);
    }

    static Rational add(Rational left, Rational right, Work work) {
        work.add(1);
        return checked(left.add(right));
    }

    static Rational multiply(Rational left, Rational right, Work work) {
        work.add(1);
        // Integer products have no cancellations. Other operations can at most double
        // the bounded operand sizes before normalization.
        if (left.denominator().equals(BigInteger.ONE) && right.denominator().equals(BigInteger.ONE)
                && magnitudeBits(left.numerator()) + magnitudeBits(right.numerator()) - 1 > MAX_RATIONAL_BITS) {
            throw new ArithmeticLimit();
        }
        return checked(left.multiply(right));
    }

    static Rational checked(Rational value) {
        checked(value.numerator());
        checked(value.denominator());
        return value;
    }

    static BigInteger checked(BigInteger value) {
        if (magnitudeBits(value) > MAX_RATIONAL_BITS) throw new ArithmeticLimit();
        return value;
    }

    static int magnitudeBits(BigInteger value) {
        return value.abs().bitLength();
    }

    static final class ArithmeticLimit extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
