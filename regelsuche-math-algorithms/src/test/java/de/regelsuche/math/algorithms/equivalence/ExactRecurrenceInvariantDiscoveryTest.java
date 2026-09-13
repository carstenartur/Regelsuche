package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.RecurrenceInvariantFormation.Bounds;
import de.regelsuche.math.algorithms.equivalence.RecurrenceInvariantFormation.Recurrence;
import de.regelsuche.math.algorithms.equivalence.ExactRecurrenceInvariantDiscovery.Status;
import de.regelsuche.math.algorithms.equivalence.ExactRecurrenceInductionVerifier.Certificate;
import de.regelsuche.math.algorithms.equivalence.ExactRecurrenceInductionVerifier.VerificationStatus;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Ordinary public mathematical controls; historical correspondence is inspected only after complete freeze. */
@Timeout(30)
class ExactRecurrenceInvariantDiscoveryTest {
    private static final Bounds BOUNDS = new Bounds(128, 256, 20_000, 200_000);
    private final ExactRecurrenceInvariantDiscovery discovery = new ExactRecurrenceInvariantDiscovery();
    private final ExactRecurrenceInductionVerifier verifier = new ExactRecurrenceInductionVerifier();

    @Test
    void freezesEveryNativeAttemptAndCertificateBeforePublicHistoricalCorrespondence() {
        var formation = formation(List.of(1L, 1L), List.of(0L, 1L), 2, List.of(-2L, -1L, 0L, 1L, 2L));
        var run = discovery.discover(formation);
        assertEquals(Status.COMPLETE_WITH_INVARIANTS, run.status());
        assertEquals(15, run.attempts().size());
        assertEquals(1, run.certificates().size());
        assertEquals(run.contentHash(), discovery.discover(formation).contentHash());
        String frozenBytes = run.toCanonicalJson();
        String frozenHash = run.contentHash();
        assertFalse(frozenBytes.contains("Cassini"));
        assertFalse(frozenBytes.contains("Fibonacci"));
        var certificate = run.certificates().getFirst().certificate();

        // Only now inspect the public historical representation class; never a formation parameter.
        new ExactPolynomialAnalysis().requireEquivalent(certificate.invariantExpression(),
            "state0*(state0+state1)-state1^2");
        assertEquals(ExactRational.NEGATIVE_ONE, certificate.lambda());
        assertEquals(ExactRational.NEGATIVE_ONE, certificate.initialValue());
        assertEquals("NATURAL_INDEX_GE_ZERO", certificate.indexDomain());
        assertEquals(frozenBytes, run.toCanonicalJson());
        assertEquals(frozenHash, run.contentHash());
        assertTrue(run.attempts().stream().allMatch(attempt -> attempt.solverResult().isPresent() && attempt.replayResult().isPresent()));
        assertTrue(run.work().solver() > 0 && run.work().solverReplay() > 0 && run.work().transitionCheck() > 0 && run.work().initialCheck() > 0);
        assertEquals(run.work().solver(), run.work().solverReplay());
        assertEquals(run.work().solver(), run.attempts().stream().mapToInt(a -> a.solverResult().orElseThrow().work().consumed()).sum());
        assertEquals(run.work().solverReplay(), run.attempts().stream().mapToInt(a -> a.replayResult().orElseThrow().work().consumed()).sum());
        assertTrue(run.work().consumed() <= formation.bounds().maxTotalWorkUnits());
    }

    @Test
    void discoversAnotherRecurrenceWithoutTheHistoricalRepresentation() {
        var formation = formation(List.of(2L), List.of(3L), 2, List.of(0L, 1L, 2L, 4L));
        var run = discovery.discover(formation);
        assertEquals(Status.COMPLETE_WITH_INVARIANTS, run.status());
        var certificate = run.certificates().getFirst().certificate();
        assertEquals(ExactRational.integer(4), certificate.lambda());
        assertEquals(ExactRational.integer(9), certificate.initialValue());
        assertEquals(VerificationStatus.CONFIRMED, verifier.verify(formation, certificate, 200_000).status());
    }

    @Test
    void lambdaZeroPreservesTheNonzeroBaseCaseAndEveryLaterZero() {
        var formation = formation(List.of(0L), List.of(3L), 1, List.of(0L));
        var run = discovery.discover(formation);
        assertEquals(Status.COMPLETE_WITH_INVARIANTS, run.status());
        var checked = run.certificates().getFirst();
        assertEquals(ExactRational.ZERO, checked.certificate().lambda());
        assertEquals(ExactRational.integer(3), checked.certificate().lambda().pow(0).multiply(checked.certificate().initialValue()));
        assertEquals(ExactRational.ZERO, checked.certificate().lambda().pow(1).multiply(checked.certificate().initialValue()));
        assertEquals(ExactRational.ZERO, checked.certificate().lambda().pow(5).multiply(checked.certificate().initialValue()));
        assertEquals(VerificationStatus.CONFIRMED, verifier.verify(formation, checked.certificate(), 200_000).status());
    }

    @Test
    void underdeterminedChartsRemainIncompleteAndNeverSupplyArbitraryFreeParameters() {
        var formation = formation(List.of(1L, 0L), List.of(2L, 3L), 2, List.of(1L));
        var run = discovery.discover(formation);
        assertEquals(Status.INCOMPLETE_WITH_INVARIANTS, run.status());
        assertTrue(run.attempts().stream().anyMatch(a -> a.status().equals("UNDERDETERMINED")));
        assertTrue(run.attempts().stream().filter(a -> a.status().equals("UNDERDETERMINED")).allMatch(a -> a.certificate().isEmpty()));
        for (var checked : run.certificates()) {
            assertTrue(checked.certificate().coefficients().stream().anyMatch(c -> !c.isZero()));
            assertEquals(ExactRational.ONE, checked.certificate().coefficients().get(checked.certificate().chartIndex()));
        }
    }

    @Test
    void omittedLambdaAndInsufficientTotalWorkAreDifferentOutcomes() {
        var omitted = formation(List.of(1L, 1L), List.of(0L, 1L), 2, List.of(0L, 1L, 2L));
        assertEquals(Status.COMPLETE_WITHOUT_INVARIANTS, discovery.discover(omitted).status());
        var original = formation(List.of(1L, 1L), List.of(0L, 1L), 2, List.of(-2L, -1L, 0L, 1L, 2L));
        var bounded = withBounds(original, new Bounds(128, 256, 20_000, 1_000));
        var exhausted = discovery.discover(bounded);
        assertTrue(exhausted.status() == Status.INCOMPLETE_WITHOUT_INVARIANTS || exhausted.status() == Status.INCOMPLETE_WITH_INVARIANTS);
        assertTrue(exhausted.work().consumed() <= 1_000);
        assertTrue(exhausted.attempts().stream().anyMatch(a -> a.status().equals("BUDGET_INCONCLUSIVE")));
        assertTrue(exhausted.work().solver() > 0);
        assertEquals(exhausted.contentHash(), discovery.discover(bounded).contentHash());
    }

    @Test
    void forgedTransitionInitialValueAndCoefficientVectorAreNotProofAuthority() {
        var formation = formation(List.of(1L, 1L), List.of(0L, 1L), 2, List.of(-1L));
        var certificate = discovery.discover(formation).certificates().getFirst().certificate();
        var badInitial = copy(certificate, certificate.coefficients(), certificate.shiftedInvariantExpression(), ExactRational.ONE);
        var badTransition = copy(certificate, certificate.coefficients(), certificate.scaledInvariantExpression(), certificate.initialValue());
        var zero = copy(certificate, certificate.coefficients().stream().map(x -> ExactRational.ZERO).toList(),
            certificate.shiftedInvariantExpression(), certificate.initialValue());
        var nativeResult = certificate.solverResult();
        var foreignResult = new ExactLinearPolynomialHoleSolver.Result("123", nativeResult.ansatzTemplate(), nativeResult.holeIds(),
            nativeResult.assumptions(), nativeResult.limits(), nativeResult.status(), nativeResult.constraints(), nativeResult.reduction(),
            nativeResult.candidate(), nativeResult.work(), nativeResult.detailCode());
        var foreignNative = new Certificate(certificate.formationHash(), certificate.lambdaIndex(), certificate.chartIndex(), certificate.lambda(),
            certificate.coefficients(), foreignResult, certificate.invariantExpression(), certificate.shiftedInvariantExpression(),
            certificate.scaledInvariantExpression(), certificate.initialValue());
        for (var forged : List.of(badInitial, badTransition, zero, foreignNative)) {
            assertNotEquals(certificate.contentHash(), forged.contentHash());
            var result = verifier.verify(formation, forged, 200_000);
            assertEquals(VerificationStatus.REFUTED, result.status());
            assertTrue(result.verified().isEmpty());
        }
        assertTrue(ExactRecurrenceInductionVerifier.VerifiedInduction.class.isSealed());
        assertTrue(ExactRecurrenceInductionVerifier.ReplayedChart.class.isSealed());
    }

    @Test
    void foreignRecurrenceInitialBasisLambdaAndWorkBoundsAreRejectedBeforeNativeWork() {
        var original = formation(List.of(1L, 1L), List.of(0L, 1L), 2, List.of(-1L));
        var certificate = discovery.discover(original).certificates().getFirst().certificate();
        List<RecurrenceInvariantFormation> foreign = List.of(
            formation(List.of(1L, 2L), List.of(0L, 1L), 2, List.of(-1L)),
            formation(List.of(1L, 1L), List.of(1L, 1L), 2, List.of(-1L)),
            formation(List.of(1L, 1L), List.of(0L, 1L), 1, List.of(-1L)),
            formation(List.of(1L, 1L), List.of(0L, 1L), 2, List.of(1L)),
            withBounds(original, new Bounds(127, 256, 20_000, 200_000)),
            withBounds(original, new Bounds(128, 255, 20_000, 200_000)),
            withBounds(original, new Bounds(128, 256, 19_999, 200_000)),
            withBounds(original, new Bounds(128, 256, 20_000, 199_999)));
        for (var changed : foreign) {
            var result = verifier.verify(changed, certificate, 200_000);
            assertEquals(VerificationStatus.REFUTED, result.status());
            assertEquals(0, result.work().solverReplay());
        }
        // Rehash the entire outer binding for different initial data: a hash-only check must still not accept the old base value.
        var changedInitial = foreign.get(1);
        var rebound = new Certificate(changedInitial.contentHash(), certificate.lambdaIndex(), certificate.chartIndex(), certificate.lambda(),
            certificate.coefficients(), certificate.solverResult(), certificate.invariantExpression(), certificate.shiftedInvariantExpression(),
            certificate.scaledInvariantExpression(), certificate.initialValue());
        var reboundResult = verifier.verify(changedInitial, rebound, 200_000);
        assertEquals(VerificationStatus.REFUTED, reboundResult.status());
        assertTrue(reboundResult.work().solverReplay() > 0 && reboundResult.work().initialCheck() > 0);
        var shortVerification = verifier.verify(original, certificate, 1);
        assertEquals(VerificationStatus.BUDGET_INCONCLUSIVE, shortVerification.status());
        assertTrue(shortVerification.verified().isEmpty());
        assertTrue(shortVerification.work().consumed() <= 1);
    }

    @Test
    void rejectsInvalidFragmentBeforeAnyNativeSolve() {
        assertThrows(IllegalArgumentException.class, () -> formation(List.of(1L, 1L, 1L, 1L), List.of(0L, 1L, 1L, 1L), 2, List.of(1L)));
        assertThrows(IllegalArgumentException.class, () -> formation(List.of(1L, 1L), List.of(0L), 2, List.of(1L)));
        assertThrows(IllegalArgumentException.class, () -> formation(List.of(1L, 1L), List.of(0L, 1L), 0, List.of(1L)));
        var valid = formation(List.of(1L, 1L), List.of(0L, 1L), 2, List.of(-1L));
        var guarded = new RecurrenceInvariantFormation(valid.recurrence(), valid.shifts(), valid.degree(), valid.basis(),
            valid.lambdas(), List.of("state0 > 0"), valid.bounds());
        var run = discovery.discover(guarded);
        assertEquals(Status.UNSUPPORTED, run.status());
        assertTrue(run.certificates().isEmpty());
        assertEquals(0, run.work().solver());
    }

    @Test
    void frozenCoefficientBoundRetainsTheExcludedUniqueSolution() {
        var original = formation(List.of(1L, 2L), List.of(0L, 1L), 2, List.of(-1L));
        var bounded = withBounds(original, new Bounds(1, 256, 20_000, 200_000));
        var run = discovery.discover(bounded);
        assertEquals(Status.COMPLETE_WITHOUT_INVARIANTS, run.status());
        var excluded = run.attempts().stream().filter(attempt -> attempt.status().equals("COEFFICIENT_BOUND")).findFirst().orElseThrow();
        assertEquals(ExactLinearPolynomialHoleSolver.Status.UNIQUE, excluded.solverResult().orElseThrow().status());
        assertEquals(ExactRational.integer(2), excluded.solverResult().orElseThrow().candidate().orElseThrow().bindings().get("coefficient1"));
        assertTrue(excluded.certificate().isEmpty());
    }

    @Test
    void initialValueBitRoomIsCheckedBeforeLargeArithmeticAndCannotIssueInduction() {
        var original = formation(List.of(1L), List.of(1L), 2, List.of(1L));
        var largeInitial = new RecurrenceInvariantFormation(new Recurrence(original.recurrence().coefficients(),
            List.of(ExactRational.integer(java.math.BigInteger.ONE.shiftLeft(200)))), original.shifts(), original.degree(),
            original.basis(), original.lambdas(), List.of(), BOUNDS);
        var run = discovery.discover(largeInitial);
        assertEquals(Status.INCOMPLETE_WITHOUT_INVARIANTS, run.status());
        var attempt = run.attempts().getFirst();
        assertEquals(ExactLinearPolynomialHoleSolver.Status.UNIQUE, attempt.solverResult().orElseThrow().status());
        assertEquals("INITIAL_SCALAR_BIT_ROOM_EXHAUSTED", attempt.detailCode());
        assertTrue(run.work().initialCheck() > 0);
        assertTrue(attempt.certificate().isEmpty());
    }

    private static RecurrenceInvariantFormation formation(List<Long> recurrence, List<Long> initial, int degree, List<Long> lambdas) {
        return new RecurrenceInvariantFormation(new Recurrence(rationals(recurrence), rationals(initial)),
            java.util.stream.IntStream.range(0, recurrence.size()).boxed().toList(), degree,
            RecurrenceInvariantFormation.homogeneousBasis(recurrence.size(), degree), rationals(lambdas), List.of(), BOUNDS);
    }
    private static List<ExactRational> rationals(List<Long> values) { return values.stream().map(ExactRational::integer).toList(); }
    private static RecurrenceInvariantFormation withBounds(RecurrenceInvariantFormation source, Bounds bounds) {
        return new RecurrenceInvariantFormation(source.recurrence(), source.shifts(), source.degree(), source.basis(), source.lambdas(), source.assumptions(), bounds);
    }
    private static Certificate copy(Certificate c, List<ExactRational> coefficients, String shifted, ExactRational initial) {
        return new Certificate(c.formationHash(), c.lambdaIndex(), c.chartIndex(), c.lambda(), coefficients, c.solverResult(),
            c.invariantExpression(), shifted, c.scaledInvariantExpression(), initial);
    }
}
