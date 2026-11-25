package de.regelsuche.math.algorithms.combinatorics;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Factor;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Problem;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanner.Strategy;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FiniteConstraintPlannerTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 100_000, 10_000);
    private static final List<BigInteger> EXCLUDE_BOTH = List.of(
        BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);

    @Test void leavesKeepTheOriginalSourceAndAvoidTheWidthLimit() {
        var factors = new ArrayList<Factor>();
        for (int leaf = 1; leaf <= 21; leaf++) factors.add(new Factor(List.of(0, leaf), EXCLUDE_BOTH));
        var problem = new Problem(22, factors, Map.of());
        var baseline = FiniteConstraintCounter.count(problem, Strategy.ASCENDING, LIMITS, () -> false);
        assertEquals("WIDTH_LIMIT", baseline.reason());
        assertTrue(baseline.count().isEmpty());
        for (Strategy strategy : List.of(Strategy.MIN_DEGREE, Strategy.MIN_FILL)) {
            var result = FiniteConstraintCounter.count(problem, strategy, LIMITS, () -> false);
            var certificate = result.certificate().orElseThrow();
            assertSame(problem, certificate.source());
            assertEquals(BigInteger.ONE.shiftLeft(21).add(BigInteger.ONE), certificate.total());
            assertTrue(certificate.steps().stream().allMatch(step -> step.output().variables().size() <= 1));
            assertTrue(FiniteConstraintVerifier.verify(problem, certificate, LIMITS, () -> false).valid());
        }
    }

    @Test void minimumFillAndMinimumDegreeMakeDifferentSourceBasedChoices() {
        var edges = new int[][] {{0, 1}, {0, 2}, {0, 3}, {0, 4}, {1, 2}, {1, 4}, {2, 5}, {3, 5}};
        var factors = Arrays.stream(edges).map(pair -> new Factor(List.of(pair[0], pair[1]), EXCLUDE_BOTH)).toList();
        var problem = new Problem(6, factors, Map.of());
        var degree = FiniteConstraintCounter.count(problem, Strategy.MIN_DEGREE, LIMITS, () -> false);
        var fill = FiniteConstraintCounter.count(problem, Strategy.MIN_FILL, LIMITS, () -> false);
        assertEquals(3, degree.certificate().orElseThrow().steps().getFirst().variable());
        assertEquals(4, fill.certificate().orElseThrow().steps().getFirst().variable());
        assertEquals(degree.count(), fill.count());
        assertEquals(degree, FiniteConstraintCounter.count(problem, LIMITS, () -> false));
        assertEquals(fill, FiniteConstraintCounter.count(problem, Strategy.MIN_FILL, LIMITS, () -> false));
    }

    @Test void plannerIsChargedAndPollsInsideGraphConstructionAndOrdering() {
        var problem = new Problem(15, List.of(new Factor(List.of(0, 1), EXCLUDE_BOTH)), Map.of());
        for (Strategy strategy : List.of(Strategy.MIN_DEGREE, Strategy.MIN_FILL)) {
            var work = new CertificateWork(new IterationLimits(25, 1000, 10_000), () -> false);
            var exhausted = assertThrows(CertificateWork.Stop.class,
                () -> FiniteConstraintPlanner.order(problem, problem.factors(), strategy, 0, work));
            assertEquals("WORK_LIMIT", exhausted.getMessage());

            var polls = new AtomicInteger();
            var cancelledWork = new CertificateWork(LIMITS, () -> polls.incrementAndGet() > 25);
            var cancelled = assertThrows(CertificateWork.Stop.class,
                () -> FiniteConstraintPlanner.order(problem, problem.factors(), strategy, 0, cancelledWork));
            assertEquals("CANCELLED", cancelled.getMessage());
            var fewCells = new CertificateWork(new IterationLimits(10_000, 60, 10_000), () -> false);
            assertEquals("TERM_LIMIT", assertThrows(CertificateWork.Stop.class,
                () -> FiniteConstraintPlanner.order(problem, problem.factors(), strategy, 0, fewCells)).getMessage());
        }
    }

    @Test void edgelessResidualsUseNoGraphEvenForManyIsolatedVariables() {
        var problem = new Problem(100_000, List.of(), Map.of());
        for (Strategy strategy : List.of(Strategy.MIN_DEGREE, Strategy.MIN_FILL)) {
            var work = new CertificateWork(new IterationLimits(3, 1, 10_000), () -> false);
            assertNull(FiniteConstraintPlanner.order(problem, List.of(), strategy, 0, work));
            assertEquals(0, work.used());
        }
    }
}
