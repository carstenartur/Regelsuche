package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.CoefficientDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static de.regelsuche.math.algorithms.polynomial.LargePrimeFactorizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class LargePrimeFactorizationVerifierTest {
    @Test void externalCertificateNeedsNoSearchCandidatesOrModulusEnumeration() {
        var ring = ring(PRIMES.getLast());
        var source = poly(ring, -8, 12, -6, 1);
        var req = FactorizationRequest.independentComplete(source, request(source).structuralLimits(), 0, 100_000_000);
        var evidence = new LargePrimeFactorization.Certificate(source, BigInteger.ONE,
            List.of(new PolynomialFactor<>(poly(ring, -2, 1), 3)));
        var result = LargePrimeFactorizationVerifier.verify(req, RESOURCES, evidence, () -> false);
        assertEquals(LargePrimeFactorization.Status.COMPLETE_CONDITIONAL, result.status(), result.reason());
        assertEquals(0, result.splitAttempts());
    }

    @Test void sourceUnitDomainMultiplicityDuplicatesAndReducibilityAreIndependentlyRejected() {
        var ring = ring(PRIMES.getFirst());
        var linear = poly(ring, -2, 1);
        var source = poly(ring, 4, -4, 1);
        var otherRing = ring(PRIMES.get(1));
        var goodItem = new PolynomialFactor<>(linear, 2);
        var candidates = List.of(
            new LargePrimeFactorization.Certificate(linear, BigInteger.ONE, List.of(goodItem)),
            new LargePrimeFactorization.Certificate(source, BigInteger.TWO, List.of(goodItem)),
            new LargePrimeFactorization.Certificate(source, BigInteger.ONE,
                List.of(new PolynomialFactor<>(source, 1))),
            new LargePrimeFactorization.Certificate(source, BigInteger.ONE,
                List.of(new PolynomialFactor<>(linear, 1), new PolynomialFactor<>(linear, 1))),
            new LargePrimeFactorization.Certificate(source, BigInteger.ONE,
                List.of(new PolynomialFactor<>(linear, Integer.MAX_VALUE))),
            new LargePrimeFactorization.Certificate(source, BigInteger.ONE,
                List.of(new PolynomialFactor<>(poly(otherRing, -2, 1), 2))),
            new LargePrimeFactorization.Certificate(source, BigInteger.ONE,
                List.of(new PolynomialFactor<>(poly(ring, -3, 1), 2))),
            new LargePrimeFactorization.Certificate(source, BigInteger.ONE,
                List.of(new PolynomialFactor<>(poly(ring, -4, 2), 2))));
        for (var candidate : candidates) {
            var result = LargePrimeFactorizationVerifier.verify(request(source), RESOURCES, candidate, () -> false);
            assertEquals(LargePrimeFactorization.Status.INVALID_EVIDENCE, result.status(), result.reason());
            assertTrue(result.certificate().isEmpty());
        }
    }

    @Test void copiedTextualDomainIdentifierCannotReplaceTheTypedPrimeContract() {
        var ring = ring(PRIMES.getFirst());
        var field = ring.coefficientDomain();
        CoefficientDomain<BigInteger> impostor = new CoefficientDomain<>() {
            @Override public String id() { return field.id(); }
            @Override public BigInteger characteristic() { return BigInteger.ZERO; }
            @Override public BigInteger zero() { return BigInteger.ZERO; }
            @Override public BigInteger one() { return BigInteger.ONE; }
            @Override public BigInteger fromInteger(BigInteger value) { return value; }
            @Override public BigInteger canonical(BigInteger value) { return value; }
            @Override public BigInteger add(BigInteger a, BigInteger b) { return a.add(b); }
            @Override public BigInteger negate(BigInteger value) { return value.negate(); }
            @Override public BigInteger multiply(BigInteger a, BigInteger b) { return a.multiply(b); }
            @Override public boolean isZero(BigInteger value) { return value.signum() == 0; }
            @Override public String canonicalText(BigInteger value) { return value.toString(); }
            @Override public int bitLength(BigInteger value) { return value.bitLength(); }
        };
        var falseRing = new PolynomialRing<>(impostor, ring.variables(), ring.monomialOrder());
        var source = poly(ring, 1, 1);
        var falseSource = poly(falseRing, 1, 1);
        var evidence = List.of(
            new LargePrimeFactorization.Certificate(falseSource, BigInteger.ONE,
                List.of(new PolynomialFactor<>(source, 1))),
            new LargePrimeFactorization.Certificate(source, BigInteger.ONE,
                List.of(new PolynomialFactor<>(falseSource, 1))));
        for (var certificate : evidence)
            assertEquals(LargePrimeFactorization.Status.INVALID_EVIDENCE,
                LargePrimeFactorizationVerifier.verify(request(source), RESOURCES, certificate, () -> false).status());
    }

    @Test void provenCompositeAndUnsupportedDomainsDoNotGetFieldCertificates() {
        var ring = ring(BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE));
        var source = poly(ring, 1, 1);
        var certificate = new LargePrimeFactorization.Certificate(source, BigInteger.ONE,
            List.of(new PolynomialFactor<>(source, 1)));
        assertEquals("COMPOSITE_MODULUS",
            LargePrimeFactorizationVerifier.verify(request(source), RESOURCES, certificate, () -> false).reason());
        assertEquals(LargePrimeFactorization.Status.UNSUPPORTED,
            LargePrimeFactorization.factor(request(source), POLICY, () -> false).status());
        var integerRing = new PolynomialRing<>(BigIntegerDomain.INSTANCE, List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        assertEquals(LargePrimeFactorization.Status.UNSUPPORTED,
            LargePrimeFactorization.factor(request(poly(integerRing, 1, 1)), POLICY, () -> false).status());
    }

    @Test void modulusBitMemoryTimeAndCancellationLimitsApplyBeforeCostlyArithmetic() {
        var ring = ring(PRIMES.getLast());
        var source = poly(ring, 1, 1); // Tiny coefficients must not hide the 381-bit modulus.
        var certificate = new LargePrimeFactorization.Certificate(source, BigInteger.ONE,
            List.of(new PolynomialFactor<>(source, 1)));
        var policies = List.of(
            new LargePrimeFactorizationVerifier.Policy(380, 100_000_000, 15_000_000_000L),
            new LargePrimeFactorizationVerifier.Policy(512, 1, 15_000_000_000L),
            new LargePrimeFactorizationVerifier.Policy(512, 100_000_000, 1));
        var reasons = List.of("MODULUS_BIT_LIMIT", "ALLOCATION_LIMIT", "TIME_LIMIT");
        for (int i = 0; i < policies.size(); i++) {
            var result = LargePrimeFactorizationVerifier.verify(request(source), policies.get(i), certificate, () -> false);
            assertEquals(LargePrimeFactorization.Status.INCONCLUSIVE, result.status());
            assertEquals(reasons.get(i), result.reason());
            assertTrue(result.certificate().isEmpty());
        }
        var cancelled = LargePrimeFactorizationVerifier.verify(request(source), RESOURCES, certificate, () -> true);
        assertEquals("CANCELLED", cancelled.reason());
        assertEquals(0, cancelled.work().totalWorkUnits());
        var hugeDegree = new SparsePolynomial<>(ring, Map.of(Monomial.of(Integer.MAX_VALUE), BigInteger.ONE));
        var generousShape = FactorizationRequest.independentComplete(hugeDegree,
            new FactorizationRequest.StructuralLimits(1, Integer.MAX_VALUE, 2, 512), 1, 100_000_000);
        var stopped = LargePrimeFactorization.factor(generousShape, POLICY, () -> false);
        assertEquals(LargePrimeFactorization.Status.INCONCLUSIVE, stopped.status());
        assertTrue(stopped.certificate().isEmpty());
    }

    @Test void verifierHasItsOwnExactWorkBoundaryAndHonorsThreadInterruption() {
        var ring = ring(PRIMES.getFirst());
        var source = poly(ring, -2, 0, 1);
        var certificate = new LargePrimeFactorization.Certificate(source, BigInteger.ONE,
            List.of(new PolynomialFactor<>(source, 1)));
        var checked = LargePrimeFactorizationVerifier.verify(request(source), RESOURCES, certificate, () -> false);
        assertEquals(LargePrimeFactorization.Status.COMPLETE_CONDITIONAL, checked.status(), checked.reason());
        long used = checked.work().totalWorkUnits();
        assertEquals("WORK_LIMIT", LargePrimeFactorizationVerifier.verify(request(source, used - 1),
            RESOURCES, certificate, () -> false).reason());
        assertEquals(LargePrimeFactorization.Status.COMPLETE_CONDITIONAL,
            LargePrimeFactorizationVerifier.verify(request(source, used), RESOURCES, certificate, () -> false).status());
        Thread.currentThread().interrupt();
        try {
            assertEquals("CANCELLED", LargePrimeFactorizationVerifier.verify(request(source),
                RESOURCES, certificate, () -> false).reason());
        } finally {
            Thread.interrupted();
        }
    }

    @Test void searchAndVerificationShareWorkAllocationAndFinalCancellationBoundaries() {
        var ring = ring(PRIMES.getFirst());
        var req = request(poly(ring, 2, -3, 1));
        AtomicInteger queries = new AtomicInteger();
        var complete = LargePrimeFactorization.factor(req, POLICY, () -> { queries.incrementAndGet(); return false; });
        assertEquals(LargePrimeFactorization.Status.COMPLETE_CONDITIONAL, complete.status(), complete.reason());
        long used = complete.work().totalWorkUnits();
        assertTrue(complete.work().stages().keySet().stream().anyMatch(s -> s.startsWith("verify.")));
        var tooLittle = LargePrimeFactorization.factor(request(req.source(), used - 1), POLICY, () -> false);
        assertEquals("WORK_LIMIT", tooLittle.reason());
        assertTrue(tooLittle.work().totalWorkUnits() <= used - 1);
        var exact = LargePrimeFactorization.factor(request(req.source(), used), POLICY, () -> false);
        assertEquals(LargePrimeFactorization.Status.COMPLETE_CONDITIONAL, exact.status(), exact.reason());
        var allocation = new LargePrimeFactorizationVerifier.Policy(512, complete.allocatedCoefficientSlots() - 1,
            15_000_000_000L);
        assertEquals("ALLOCATION_LIMIT", LargePrimeFactorization.factor(req,
            new LargePrimeFactorization.Policy(allocation, 256, 142), () -> false).reason());
        AtomicInteger finalQueries = new AtomicInteger();
        var lateCancel = LargePrimeFactorization.factor(req, POLICY,
            () -> finalQueries.incrementAndGet() >= queries.get());
        assertEquals("CANCELLED", lateCancel.reason());
        assertTrue(lateCancel.certificate().isEmpty());
    }
}
