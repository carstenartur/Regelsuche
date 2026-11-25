package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.AffineImplication.Status.PROVED;
import static de.regelsuche.math.algorithms.linalg.AffineImplication.Status.REFUTED;
import static de.regelsuche.math.algorithms.linalg.AffineImplication.Status.UNAVAILABLE;
import static de.regelsuche.scalar.ExactRational.NEGATIVE_ONE;
import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.AffineImplication.Counterexample;
import de.regelsuche.math.algorithms.linalg.AffineImplication.Proof;
import de.regelsuche.math.algorithms.linalg.AffineImplication.Request;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Infeasible;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Optimal;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Problem;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Unbounded;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AffineImplicationTest {
    private static final IterationLimits LIMITS = new IterationLimits(5_000_000, 100_000, 10_000);

    @Test
    void provesAndRefutesAffineBoundsUsingTheSearchedOptimum() {
        var source = new Problem(List.of(List.of(ONE, ONE), List.of(NEGATIVE_ONE, ZERO),
            List.of(ZERO, NEGATIVE_ONE)), List.of(integer(4), ZERO, ZERO), List.of(ONE, ONE));
        var valid = new Request(source, integer(4));
        var proved = AffineImplication.search(valid, LIMITS, () -> false);
        assertEquals(PROVED, proved.status(), proved.reason());
        var proof = assertInstanceOf(Proof.class, proved.evidence().orElseThrow());
        assertInstanceOf(Optimal.class, proof.certificate());
        assertTrue(AffineImplicationVerifier.verify(valid, proof, LIMITS, () -> false).valid());

        var invalid = new Request(source, new ExactRational(BigInteger.valueOf(39), BigInteger.TEN));
        var refuted = AffineImplication.search(invalid, LIMITS, () -> false);
        assertEquals(REFUTED, refuted.status(), refuted.reason());
        var point = assertInstanceOf(Counterexample.class, refuted.evidence().orElseThrow());
        assertEquals(integer(4), point.x().getFirst().add(point.x().get(1)));
        assertTrue(AffineImplicationVerifier.verify(invalid, point, LIMITS, () -> false).valid());
    }

    @Test
    void provesVacuousImplicationOnlyWithCheckedInfeasiblePremises() {
        var source = new Problem(List.of(List.of(ONE), List.of(NEGATIVE_ONE)),
            List.of(ZERO, NEGATIVE_ONE), List.of(integer(17)));
        var request = new Request(source, integer(-100));
        var result = AffineImplication.search(request, LIMITS, () -> false);
        assertEquals(PROVED, result.status());
        var proof = assertInstanceOf(Proof.class, result.evidence().orElseThrow());
        assertInstanceOf(Infeasible.class, proof.certificate());
        assertTrue(AffineImplicationVerifier.verify(request, proof, LIMITS, () -> false).valid());
    }

    @Test
    void turnsAnUnboundedRayIntoAFiniteStrictCounterexample() {
        var source = new Problem(List.of(List.of(NEGATIVE_ONE)), List.of(ZERO), List.of(ONE));
        var request = new Request(source, integer(1000));
        var result = AffineImplication.search(request, LIMITS, () -> false);
        assertEquals(REFUTED, result.status(), result.reason());
        var point = assertInstanceOf(Counterexample.class, result.evidence().orElseThrow());
        assertTrue(point.x().getFirst().compareTo(integer(1000)) > 0);
        assertTrue(AffineImplicationVerifier.verify(request, point, LIMITS, () -> false).valid());
    }

    @Test
    void bindsBothThePremisesAndTheConclusionToEveryWitness() {
        var source = new Problem(List.of(List.of(ONE), List.of(NEGATIVE_ONE)),
            List.of(integer(2), ZERO), List.of(ONE));
        var request = new Request(source, integer(2));
        var searched = AffineImplication.search(request, LIMITS, () -> false);
        assertEquals(PROVED, searched.status());
        var proof = searched.evidence().orElseThrow();
        assertEquals("SOURCE_MISMATCH", AffineImplicationVerifier.verify(
            new Request(source, ONE), proof, LIMITS, () -> false).reason());
        var alteredRows = new Problem(source.a(), List.of(integer(3), ZERO), source.c());
        assertEquals("SOURCE_MISMATCH", AffineImplicationVerifier.verify(
            new Request(alteredRows, integer(2)), proof, LIMITS, () -> false).reason());
        var alteredObjective = new Problem(source.a(), source.b(), List.of(integer(2)));
        assertEquals("SOURCE_MISMATCH", AffineImplicationVerifier.verify(
            new Request(alteredObjective, integer(2)), proof, LIMITS, () -> false).reason());
        var refuted = new Request(source, ONE);
        var point = AffineImplication.search(refuted, LIMITS, () -> false).evidence().orElseThrow();
        assertEquals("SOURCE_MISMATCH", AffineImplicationVerifier.verify(
            request, point, LIMITS, () -> false).reason());
    }

    @Test
    void independentVerifierRejectsForgedProofsAndCounterexamples() {
        var source = new Problem(List.of(List.of(ONE), List.of(NEGATIVE_ONE)),
            List.of(integer(2), ZERO), List.of(ONE));
        var request = new Request(source, ONE);
        var trueOptimal = new Optimal(source, List.of(integer(2)), List.of(ONE, ZERO));
        assertEquals(LinearProgramVerifier.Status.INVALID, AffineImplicationVerifier.verify(request,
            new Proof(request, trueOptimal), LIMITS, () -> false).status());
        assertEquals(LinearProgramVerifier.Status.INVALID, AffineImplicationVerifier.verify(request,
            new Proof(request, new Optimal(source, List.of(ONE), List.of(ZERO, ZERO))),
            LIMITS, () -> false).status());
        assertEquals(LinearProgramVerifier.Status.INVALID, AffineImplicationVerifier.verify(request,
            new Counterexample(request, List.of(ONE)), LIMITS, () -> false).status());
        assertEquals(LinearProgramVerifier.Status.INVALID, AffineImplicationVerifier.verify(request,
            new Counterexample(request, List.of(integer(3))), LIMITS, () -> false).status());
        assertEquals(LinearProgramVerifier.Status.INVALID, AffineImplicationVerifier.verify(request,
            new Counterexample(request, List.of(ONE, ZERO)), LIMITS, () -> false).status());
        var unbounded = new Problem(List.of(List.of(NEGATIVE_ONE)), List.of(ZERO), List.of(ONE));
        var invalidProof = new Request(unbounded, ONE);
        assertEquals(LinearProgramVerifier.Status.INVALID, AffineImplicationVerifier.verify(invalidProof,
            new Proof(invalidProof, new Unbounded(unbounded, List.of(ZERO), List.of(ONE))),
            LIMITS, () -> false).status());
    }

    @Test
    void sharesOneBudgetAcrossSearchConstructionAndIndependentChecking() {
        var source = new Problem(List.of(List.of(ONE), List.of(NEGATIVE_ONE)),
            List.of(integer(2), ZERO), List.of(ONE));
        var request = new Request(source, integer(2));
        var lp = LinearProgramSearch.search(source, LIMITS, () -> false);
        var complete = AffineImplication.search(request, LIMITS, () -> false);
        assertEquals(PROVED, complete.status());
        assertTrue(complete.work() > lp.work());
        var spent = AffineImplication.search(request, LIMITS.withMaximumWork(lp.work()), () -> false);
        assertEquals(UNAVAILABLE, spent.status());
        assertEquals("WORK_LIMIT", spent.reason());
        assertTrue(spent.evidence().isEmpty());
        var lastUnit = AffineImplication.search(request, LIMITS.withMaximumWork(complete.work() - 1), () -> false);
        assertEquals(UNAVAILABLE, lastUnit.status());
        var exact = AffineImplication.search(request, LIMITS.withMaximumWork(complete.work()), () -> false);
        assertEquals(PROVED, exact.status());
        assertEquals(complete.work(), exact.work());
    }

    @Test
    void incompleteSearchAndUnavailableReplayDoNotClaimTruthOrFalsehood() {
        var source = new Problem(List.of(List.of(ONE)), List.of(integer(2)), List.of(ONE));
        var request = new Request(source, integer(2));
        for (var limits : List.of(new IterationLimits(1, 100_000, 10_000),
                new IterationLimits(5_000_000, 1, 10_000))) {
            var result = AffineImplication.search(request, limits, () -> false);
            assertEquals(UNAVAILABLE, result.status());
            assertTrue(result.evidence().isEmpty());
        }
        var cancelled = AffineImplication.search(request, LIMITS, () -> true);
        assertEquals("CANCELLED", cancelled.reason());
        assertEquals(UNAVAILABLE, cancelled.status());
        var enormous = new Request(source,
            ExactRational.integer(BigInteger.ONE.shiftLeft(IterationLimits.MAX_RUNTIME_BITS)));
        assertEquals("RUNTIME_BIT_LIMIT", AffineImplication.search(enormous, LIMITS, () -> false).reason());
        var evidence = AffineImplication.search(request, LIMITS, () -> false).evidence().orElseThrow();
        assertEquals(LinearProgramVerifier.Status.UNAVAILABLE,
            AffineImplicationVerifier.verify(request, evidence, LIMITS, () -> true).status());
        assertEquals(LinearProgramVerifier.Status.UNAVAILABLE,
            AffineImplicationVerifier.verify(request, evidence,
                new IterationLimits(1, 100_000, 10_000), () -> false).status());
    }

    @Test
    void observesCancellationBeforeReturningACheckedConclusion() {
        var source = new Problem(List.of(List.of(ONE)), List.of(integer(2)), List.of(ONE));
        var request = new Request(source, integer(2));
        var visits = new AtomicInteger();
        assertEquals(PROVED, AffineImplication.search(request, LIMITS, () -> {
            visits.incrementAndGet();
            return false;
        }).status());
        int allVisits = visits.get();
        visits.set(0);
        var result = AffineImplication.search(request, LIMITS, () -> visits.incrementAndGet() >= allVisits);
        assertEquals(UNAVAILABLE, result.status());
        assertEquals("CANCELLED", result.reason());
        assertTrue(result.evidence().isEmpty());
    }

    private static ExactRational integer(long value) { return ExactRational.integer(value); }
}
