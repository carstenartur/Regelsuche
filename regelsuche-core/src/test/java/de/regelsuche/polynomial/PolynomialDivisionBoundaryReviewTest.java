package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Independent controls for the two concrete-type guards and pre-operation admission. */
class PolynomialDivisionBoundaryReviewTest {
    private static final PrimeField FIELD = PrimeField.of(101);

    @Test void concreteDomainStillInvokesTheSuppliedSameIdFieldAfterEachAcceptedCharge() {
        var ring = ring(FIELD);
        var source = view(ring, 5, 4, 0, 0, 3);
        var divisor = view(ring, 3, 2);
        var supplied = new TracedField();
        var failure = new IllegalStateException("caller-owned second division failure");
        supplied.failure = failure;
        var trace = new Admission(Long.MAX_VALUE);

        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> source.divideAndRemainder(divisor, supplied, trace, "caller")));
        assertEquals(List.of(BigInteger.valueOf(3), BigInteger.valueOf(46)), supplied.dividends);
        assertEquals(List.of("caller.iterations:1", "caller.coefficient-divisions:1",
            "caller.coefficient-updates:2", "caller.coefficient-updates:2",
            "caller.iterations:1", "caller.coefficient-divisions:1"), trace.accepted);
        assertEquals(8, trace.total);

        var limitedField = new TracedField();
        limitedField.failure = failure;
        var limited = new Admission(7);
        assertThrows(Refused.class, () -> source.divideAndRemainder(divisor, limitedField, limited, "caller"));
        assertEquals(List.of(BigInteger.valueOf(3)), limitedField.dividends);
        assertEquals(trace.accepted.subList(0, 5), limited.accepted);
        assertEquals(7, limited.total);

        var beforeFirstDivision = new TracedField();
        assertThrows(Refused.class,
            () -> source.divideAndRemainder(divisor, beforeFirstDivision, new Admission(1), "caller"));
        assertTrue(beforeFirstDivision.dividends.isEmpty());
    }

    @Test void sameIdCustomDomainDoesNotEnterTheConcretePrimeFieldSpecialization() {
        var customDomain = new TracedField();
        var ring = ring(customDomain);
        var source = view(ring, 5, 4, 0, 0, 3);
        var leading = new InverseObservedInteger("2");
        var divisor = UnivariatePolynomialView.of(ring, List.of(BigInteger.valueOf(3), leading));
        var trace = new Admission(Long.MAX_VALUE);

        var result = source.divideAndRemainder(divisor, FIELD, trace, "custom-domain");

        assertEquals(4, leading.inversions, "a custom coefficient domain keeps per-iteration field division");
        assertTrue(customDomain.dividends.isEmpty(), "the supplied field owns scalar division");
        assertEquals(3, result.quotient().degree());
        assertTrue(result.remainder().isZero() || result.remainder().degree() < divisor.degree());
        assertEquals(source, result.quotient().multiply(divisor).add(result.remainder()));
        assertEquals(24, trace.total);
    }

    @Test void concretePrimeInverseWaitsForBothOriginalChargesAndRemainsInvocationLocal() {
        var ring = ring(FIELD);
        var source = view(ring, 5, 4, 0, 0, 3);
        for (long limit : new long[] {0, 1, 2, 7, 8, 24}) {
            var leading = new InverseObservedInteger("2");
            var divisor = UnivariatePolynomialView.of(ring, List.of(BigInteger.valueOf(3), leading));
            var trace = new Admission(limit);
            if (limit < 24) assertThrows(Refused.class, () -> source.divideAndRemainder(divisor, FIELD, trace, "prime"));
            else assertEquals(source, source.divideAndRemainder(divisor, FIELD, trace, "prime").quotient()
                .multiply(divisor).add(view(ring, 71)));
            assertEquals(limit < 2 ? 0 : 1, leading.inversions, "inverse admission at budget " + limit);
            assertTrue(trace.total <= limit);
        }
    }

    @Test void inputValidationAndNoWorkReturnsPrecedeAllDivisionPreparation() {
        var ring = ring(FIELD);
        var source = view(ring, 3);
        var divisor = view(ring, 3, 2);
        var zero = UnivariatePolynomialView.zero(ring);
        var otherRing = ring(PrimeField.of(103));

        assertThrows(IllegalArgumentException.class,
            () -> source.divideAndRemainder(UnivariatePolynomialView.zero(otherRing), null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> source.divideAndRemainder(zero, PrimeField.of(103), null, null));
        assertThrows(ArithmeticException.class, () -> source.divideAndRemainder(zero, FIELD, null, null));
        assertThrows(NullPointerException.class, () -> zero.divideAndRemainder(divisor, null, null, null));
        assertSame(zero, zero.divideAndRemainder(divisor, FIELD, null, null).remainder());
        assertSame(source, source.divideAndRemainder(divisor, FIELD, null, null).remainder());
    }

    private static PolynomialRing<BigInteger> ring(CoefficientDomain<BigInteger> domain) {
        return new PolynomialRing<>(domain, List.of(new PolynomialVariable("x")), PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    }
    private static UnivariatePolynomialView<BigInteger> view(PolynomialRing<BigInteger> ring, long... values) {
        return UnivariatePolynomialView.of(ring, java.util.Arrays.stream(values).mapToObj(BigInteger::valueOf).toList());
    }
    private static final class Admission implements PolynomialWorkSink {
        private final long limit;
        private long total;
        private final List<String> accepted = new ArrayList<>();
        private Admission(long limit) { this.limit = limit; }
        @Override public void consume(String stage, long units) {
            if (units > limit - total) throw new Refused();
            total += units;
            accepted.add(stage + ":" + units);
        }
    }
    private static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    /** Numeric value stays immutable; only calls to the supplied value's inverse operation are observed. */
    private static final class InverseObservedInteger extends BigInteger {
        private static final long serialVersionUID = 1L;
        private int inversions;
        private InverseObservedInteger(String value) { super(value); }
        @Override public BigInteger modInverse(BigInteger modulus) {
            inversions++;
            return super.modInverse(modulus);
        }
    }
    private static final class TracedField implements ExactField<BigInteger> {
        private final List<BigInteger> dividends = new ArrayList<>();
        private RuntimeException failure;
        public String id() { return FIELD.id(); }
        public BigInteger characteristic() { return FIELD.characteristic(); }
        public BigInteger fromInteger(BigInteger value) { return FIELD.fromInteger(value); }
        public BigInteger zero() { return FIELD.zero(); }
        public BigInteger one() { return FIELD.one(); }
        public BigInteger canonical(BigInteger value) { return FIELD.canonical(value); }
        public BigInteger add(BigInteger left, BigInteger right) { return FIELD.add(left, right); }
        public BigInteger negate(BigInteger value) { return FIELD.negate(value); }
        public BigInteger multiply(BigInteger left, BigInteger right) { return FIELD.multiply(left, right); }
        public boolean isZero(BigInteger value) { return FIELD.isZero(value); }
        public String canonicalText(BigInteger value) { return FIELD.canonicalText(value); }
        public int bitLength(BigInteger value) { return FIELD.bitLength(value); }
        public BigInteger divide(BigInteger left, BigInteger right) {
            dividends.add(left);
            if (failure != null && dividends.size() == 2) throw failure;
            return FIELD.divide(left, right);
        }
    }
}
