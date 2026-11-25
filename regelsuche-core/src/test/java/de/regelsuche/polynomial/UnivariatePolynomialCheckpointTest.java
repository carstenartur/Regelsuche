package de.regelsuche.polynomial;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Interrupt individual construction phases without timing-sensitive assertions. */
class UnivariatePolynomialCheckpointTest {
    private static final int SIZE = 100_000;
    private final ObservedField field = new ObservedField();
    private final PolynomialRing<BigInteger> ring = new PolynomialRing<>(field,
        List.of(new PolynomialVariable("x")), PolynomialRing.MonomialOrder.LEXICOGRAPHIC);

    @Test void checkedSparseInputStopsDuringDenseZeroFill() {
        var source = new SparsePolynomial<>(ring,
            Map.of(Monomial.of(0), BigInteger.ONE, Monomial.of(SIZE - 1), BigInteger.ONE));
        field.reset();
        assertThrows(Stopped.class, () -> UnivariatePolynomialView.from(source,
            stopWhen(() -> field.zeros >= 2048), "convert"));
        assertTrue(field.zeros >= 2048 && field.zeros < SIZE / 4);
        assertEquals(0, field.canonicalized, "stop before the later canonicalization pass");
    }

    @Test void checkedDenseInputStopsDuringCanonicalization() {
        var values = Collections.nCopies(SIZE, BigInteger.ONE);
        assertThrows(Stopped.class, () -> UnivariatePolynomialView.of(ring, values,
            stopWhen(() -> field.canonicalized >= 2048), "construct"));
        assertTrue(field.canonicalized >= 2048 && field.canonicalized < SIZE / 4);
    }

    @Test void checkedDenseInputStopsDuringTrailingZeroTrimming() {
        var values = new ArrayList<>(Collections.nCopies(SIZE, BigInteger.ZERO));
        values.set(0, BigInteger.ONE);
        assertThrows(Stopped.class, () -> UnivariatePolynomialView.of(ring, values,
            stopWhen(() -> field.zeroTests >= 2048), "construct"));
        assertEquals(SIZE, field.canonicalized);
        assertTrue(field.zeroTests >= 2048 && field.zeroTests < SIZE / 4);
    }

    @Test void budgetedArithmeticPollsWhenCanonicalizingItsResult() {
        var value = UnivariatePolynomialView.of(ring, Collections.nCopies(SIZE, BigInteger.ONE));
        field.reset();
        assertThrows(Stopped.class, () -> value.derivative(
            stopWhen(() -> field.canonicalized >= 2048), "derivative"));
        assertTrue(field.canonicalized >= 2048 && field.canonicalized < SIZE / 4);
    }

    @Test void checkedSparseOutputStopsDuringDenseScanning() {
        var value = UnivariatePolynomialView.of(ring, Collections.nCopies(SIZE, BigInteger.ONE));
        field.reset();
        assertThrows(Stopped.class, () -> value.toSparsePolynomial(
            stopWhen(() -> field.zeroTests >= 2048), "output"));
        assertTrue(field.zeroTests >= 2048 && field.zeroTests < SIZE / 4);
    }

    @Test void checkedSparseOutputAlsoPollsItsSecondCanonicalizationPass() {
        var value = UnivariatePolynomialView.of(ring, Collections.nCopies(SIZE, BigInteger.ONE));
        field.reset();
        assertThrows(Stopped.class, () -> value.toSparsePolynomial(
            stopWhen(() -> field.canonicalized >= 2048), "output"));
        assertTrue(field.canonicalized >= 2048 && field.canonicalized < SIZE / 4);
    }

    @Test void checkedConstructionOwnsItsValuesAndDoesNotChargeAlreadyPrepaidWork() {
        var values = new ArrayList<>(List.of(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO));
        long[] charged = {0};
        int[] polls = {0};
        PolynomialWorkSink sink = new PolynomialWorkSink() {
            @Override public void consume(String stage, long units) { charged[0] += units; }
            @Override public void checkpoint(String stage) { polls[0]++; }
        };
        var checked = UnivariatePolynomialView.of(ring, values, sink, "construct");
        var expected = UnivariatePolynomialView.of(ring, values);
        values.set(0, BigInteger.ZERO);
        assertEquals(expected, checked);
        assertThrows(UnsupportedOperationException.class, () -> checked.coefficients().set(0, BigInteger.ZERO));
        assertEquals(checked, UnivariatePolynomialView.from(checked.toSparsePolynomial(sink, "out"), sink, "in"));
        assertTrue(polls[0] > 0);
        assertEquals(0, charged[0]);
    }

    private static PolynomialWorkSink stopWhen(BooleanSupplier stopped) {
        return new PolynomialWorkSink() {
            @Override public void consume(String stage, long units) { }
            @Override public void checkpoint(String stage) { if (stopped.getAsBoolean()) throw new Stopped(); }
        };
    }

    private static final class Stopped extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class ObservedField implements ExactField<BigInteger> {
        private final PrimeField delegate = PrimeField.of(2);
        private int zeros;
        private int canonicalized;
        private int zeroTests;
        void reset() { zeros = 0; canonicalized = 0; zeroTests = 0; }
        @Override public String id() { return "checkpoint-test-f2"; }
        @Override public BigInteger characteristic() { return BigInteger.TWO; }
        @Override public BigInteger fromInteger(BigInteger value) { return delegate.fromInteger(value); }
        @Override public BigInteger zero() { zeros++; return BigInteger.ZERO; }
        @Override public BigInteger one() { return BigInteger.ONE; }
        @Override public BigInteger canonical(BigInteger value) { canonicalized++; return delegate.canonical(value); }
        @Override public BigInteger add(BigInteger left, BigInteger right) { return delegate.add(left, right); }
        @Override public BigInteger negate(BigInteger value) { return delegate.negate(value); }
        @Override public BigInteger multiply(BigInteger left, BigInteger right) { return delegate.multiply(left, right); }
        @Override public boolean isZero(BigInteger value) { zeroTests++; return delegate.isZero(value); }
        @Override public String canonicalText(BigInteger value) { return delegate.canonicalText(value); }
        @Override public int bitLength(BigInteger value) { return delegate.bitLength(value); }
        @Override public BigInteger divide(BigInteger left, BigInteger right) { return delegate.divide(left, right); }
    }
}
