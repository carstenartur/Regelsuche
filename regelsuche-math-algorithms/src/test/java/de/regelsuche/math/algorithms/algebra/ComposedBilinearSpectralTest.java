package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.Status.*;
import static de.regelsuche.scalar.ExactRational.*;
import static org.junit.jupiter.api.Assertions.*;

class ComposedBilinearSpectralTest {
    private static final IterationLimits LIMITS = new IterationLimits(20_000_000, 500_000, 20_000);

    @Test void anIndependentlyCheckedSpectralStageActuallyExecutesInsideTheBatchProcedure() {
        var fixture = fixture();
        var problem = new SpectralBatchRepair.Problem(matrix(1, 2, 0, -1), matrix(1, -1, 0, 1),
            matrix(1, 1, 0, 1), 2);
        var predictors = List.<ExactMatrixExpression>of(new ExactMatrixExpression.Identity(2),
            new ExactMatrixExpression.Matrix("negative identity", matrix(-1, 0, 0, -1)));
        var certificate = SpectralBatchRepair.construct(problem, predictors, LIMITS, () -> false).requireValue();
        assertEquals(1, certificate.syndromeWidth());
        var stages = new ArrayList<>(fixture.plan().stages());
        stages.set(0, new ComposedBilinearPlan.Spectral(problem, certificate));
        var plan = new ComposedBilinearPlan(fixture.plan().program(), 2, stages);
        var checked = ComposedBilinearVerifier.prepare(fixture.request(), 2, plan, LIMITS, () -> false);
        assertEquals(VERIFIED, checked.status(), checked.detail());
        var prepared = checked.requireValue();
        assertInstanceOf(ComposedBilinearPlan.Spectral.class, prepared.plan().stages().getFirst());
        assertTrue(prepared.cost().total() > prepared.directCost().total(), "This valid supplied repair is deliberately not a speed claim");
        var left = List.of(List.of(ONE, integer(2)), List.of(integer(-3), integer(4)));
        var right = List.of(List.of(integer(5), integer(-1)), List.of(integer(2), integer(3)));
        assertEquals(List.of(List.of(integer(25), integer(2)), List.of(integer(10), integer(-12))),
            prepared.apply(left, right, LIMITS, () -> false).requireValue());
        var replay = prepared.apply(left, right, LIMITS, () -> false);
        assertEquals(STOPPED, prepared.apply(left, right, LIMITS.withMaximumWork(replay.work() - 1), () -> false).status());
    }

    @Test void spectralCandidatesEnterRealStageSelectionAndLosingRepairsKeepTheFairBaseline() {
        var fixture = fixture();
        var found = ComposedBilinearSearch.search(fixture.request(), fixture.plan().program(), 2,
            ComposedBilinearSearch.Policy.defaults(), 27, LIMITS, () -> false);
        assertEquals(VERIFIED, found.status(), found.detail());
        var discovery = found.requireValue();
        assertEquals(27, discovery.accounting().attemptedCandidates());
        assertEquals(10, discovery.prepared().cost().total());
        assertEquals(discovery.prepared().directCost(), discovery.prepared().cost());
        assertEquals(3, discovery.attempts().stream().filter(a -> a.provider() == ComposedBilinearSearch.Provider.SPECTRAL).count());
        assertTrue(discovery.attempts().stream().filter(a -> a.provider() == ComposedBilinearSearch.Provider.SPECTRAL)
            .allMatch(a -> a.proposals().size() == 5 && a.work() > 0));
        assertTrue(discovery.prepared().plan().stages().stream().allMatch(s -> s instanceof ComposedBilinearPlan.Linear));
        long nested = discovery.attempts().stream().mapToLong(ComposedBilinearSearch.Attempt::work).sum();
        assertTrue(discovery.accounting().compositionWork() >= nested);
        var tight = ComposedBilinearSearch.search(fixture.request(), fixture.plan().program(), 2,
            ComposedBilinearSearch.Policy.defaults(), 26, LIMITS, () -> false);
        assertEquals(STOPPED, tight.status());
        assertEquals("CANDIDATE_LIMIT", tight.detail());
        assertTrue(tight.value().isEmpty());
    }

    @Test void spectralEvidenceCannotMoveToAnotherSourceBasisOrBatchWidth() {
        var fixture = fixture();
        var problem = new SpectralBatchRepair.Problem(matrix(1, 2, 0, -1), matrix(1, -1, 0, 1),
            matrix(1, 1, 0, 1), 2);
        var certificate = SpectralBatchRepair.construct(problem,
            List.of(new ExactMatrixExpression.Identity(2), new ExactMatrixExpression.Identity(2)),
            LIMITS, () -> false).requireValue();
        var problems = List.of(
            new SpectralBatchRepair.Problem(matrix(1, 0, 0, 1), problem.basis(), problem.inverse(), 2),
            new SpectralBatchRepair.Problem(problem.source(), matrix(1, 0, 0, 1), matrix(1, 0, 0, 1), 2),
            new SpectralBatchRepair.Problem(problem.source(), problem.basis(), problem.inverse(), 3));
        for (var changed : problems) {
            var stages = new ArrayList<>(fixture.plan().stages());
            stages.set(0, new ComposedBilinearPlan.Spectral(changed, certificate));
            var tampered = new ComposedBilinearPlan(fixture.plan().program(), 2, stages);
            assertEquals(INVALID, ComposedBilinearVerifier.prepare(fixture.request(), 2, tampered,
                LIMITS, () -> false).status());
        }
    }

    @Test void aNoninvolutionIsAnUnsupportedSpectralProposalAndStillHasAnExactProcedure() {
        var identity = List.of(List.of(ONE, ZERO), List.of(ZERO, ONE));
        var program = new BilinearProgram(List.of(List.of(integer(2), integer(3)), List.of(integer(5), integer(7))), identity, identity);
        var tensor = new BilinearProgram.Tensor(2, 2,
            List.of(List.of(integer(2), ZERO, integer(3), ZERO), List.of(ZERO, integer(5), ZERO, integer(7))));
        var request = new BilinearProgram.Request(tensor, program.u(), program.v(), 2);
        var found = ComposedBilinearSearch.search(request, program, 1, ComposedBilinearSearch.Policy.defaults(),
            22, LIMITS, () -> false);
        assertEquals(VERIFIED, found.status(), found.detail());
        assertTrue(found.requireValue().attempts().stream().anyMatch(a -> a.stage() == ComposedBilinearSearch.Stage.U
            && a.provider() == ComposedBilinearSearch.Provider.SPECTRAL_BASIS && a.status() == UNSUPPORTED && a.work() > 0));
        assertEquals(List.of(List.of(integer(16), integer(57))), found.requireValue().prepared().apply(
            List.of(List.of(ONE, integer(2))), List.of(List.of(integer(2), integer(3))), LIMITS, () -> false).requireValue());
    }

    private record Fixture(BilinearProgram.Request request, ComposedBilinearPlan plan) {}

    private static Fixture fixture() {
        var identity = List.of(List.of(ONE, ZERO), List.of(ZERO, ONE));
        var program = new BilinearProgram(List.of(List.of(ONE, integer(2)), List.of(ZERO, integer(-1))), identity, identity);
        var tensor = new BilinearProgram.Tensor(2, 2,
            List.of(List.of(ONE, ZERO, integer(2), ZERO), List.of(ZERO, ZERO, ZERO, integer(-1))));
        var request = new BilinearProgram.Request(tensor, program.u(), program.v(), 2);
        var prepared = ComposedBilinearSearch.search(request, program, 2, ComposedBilinearSearch.Policy.residualOnly(),
            12, LIMITS, () -> false).requireValue().prepared();
        return new Fixture(request, prepared.plan());
    }

    private static PolynomialMatrix matrix(long a, long b, long c, long d) {
        return new PolynomialMatrix(List.of(List.of(Polynomial.constant(Rational.of(a)), Polynomial.constant(Rational.of(b))),
            List.of(Polynomial.constant(Rational.of(c)), Polynomial.constant(Rational.of(d)))));
    }
}
